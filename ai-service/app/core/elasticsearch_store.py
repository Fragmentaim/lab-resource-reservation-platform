"""Persistent lexical retrieval for knowledge chunks.

Qdrant remains the dense-vector store. Elasticsearch owns the BM25/inverted
index and returns payloads in the same shape as the vector retrieval path so
the RRF and rerank stages stay provider-neutral.
"""

from __future__ import annotations

from typing import Iterable, List, Optional

from elasticsearch import BadRequestError, Elasticsearch, helpers

from app.config import settings


_client: Elasticsearch | None = None


def get_client() -> Elasticsearch:
    global _client
    if _client is None:
        kwargs = {
            "hosts": [settings.elasticsearch_url],
            "verify_certs": settings.elasticsearch_verify_certs,
            "request_timeout": settings.elasticsearch_request_timeout_seconds,
            "retry_on_timeout": True,
            "max_retries": 2,
        }
        if settings.elasticsearch_username:
            kwargs["basic_auth"] = (
                settings.elasticsearch_username,
                settings.elasticsearch_password,
            )
        client = Elasticsearch(**kwargs)
        if not client.ping():
            raise ConnectionError(
                f"Elasticsearch is unavailable at {settings.elasticsearch_url}"
            )
        _client = client
    return _client


def ensure_index() -> None:
    client = get_client()
    index = settings.elasticsearch_index
    if client.indices.exists(index=index):
        return

    try:
        client.indices.create(
            index=index,
            settings={
                "index": {
                    "number_of_shards": 1,
                    "number_of_replicas": 0,
                }
            },
            mappings={
                "dynamic": "strict",
                "properties": {
                    "document_id": {"type": "long"},
                    "doc_version": {"type": "keyword"},
                    "chunk_id": {"type": "keyword"},
                    "chunk_index": {"type": "integer"},
                    "content": _text_mapping(),
                    "page_no": {"type": "integer"},
                    "section_title": _text_mapping(),
                    "title_path": {"type": "keyword"},
                    "title_path_text": _text_mapping(),
                    "content_hash": {"type": "keyword"},
                    "token_count": {"type": "integer"},
                    "char_start": {"type": "integer"},
                    "char_end": {"type": "integer"},
                    "block_type": {"type": "keyword"},
                    "chunk_strategy": {"type": "keyword"},
                    "parser_provider": {"type": "keyword"},
                },
            },
        )
    except BadRequestError as exc:
        # A timed-out create request may have succeeded before the transport
        # retries it. Concurrent workers can also race during first startup.
        if exc.error != "resource_already_exists_exception":
            raise


def index_chunks(document_id: int, doc_version: str, chunks: List[dict]) -> int:
    if not chunks:
        return 0

    ensure_index()
    actions = [
        {
            "_op_type": "index",
            "_index": settings.elasticsearch_index,
            "_id": chunk["chunk_id"],
            "_source": _chunk_source(document_id, doc_version, chunk),
        }
        for chunk in chunks
    ]
    success, _ = helpers.bulk(
        get_client(),
        actions,
        refresh="wait_for",
        raise_on_error=True,
        request_timeout=settings.elasticsearch_request_timeout_seconds,
    )
    return int(success)


def index_payloads(payloads: Iterable[dict]) -> int:
    """Bulk-index existing Qdrant payloads during a one-time migration."""
    ensure_index()
    success, _ = helpers.bulk(
        get_client(),
        _payload_actions(payloads),
        refresh="wait_for",
        raise_on_error=True,
        request_timeout=settings.elasticsearch_request_timeout_seconds,
    )
    return int(success)


def search(
    query: str,
    top_k: int,
    document_ids: Optional[List[int]] = None,
    document_versions: Optional[dict[int, str]] = None,
) -> list[dict]:
    if document_ids is not None and not document_ids:
        return []
    if not query.strip():
        return []

    ensure_index()
    bool_query: dict = {
        "must": [{
            "multi_match": {
                "query": query,
                "fields": [
                    "section_title^3",
                    "title_path_text^2",
                    "content",
                ],
                "type": "best_fields",
            }
        }]
    }
    if document_versions:
        allowed_ids = set(document_ids) if document_ids is not None else None
        scopes = [
            {
                "bool": {
                    "must": [
                        {"term": {"document_id": document_id}},
                        {"term": {"doc_version": doc_version}},
                    ]
                }
            }
            for document_id, doc_version in document_versions.items()
            if doc_version and (allowed_ids is None or document_id in allowed_ids)
        ]
        if not scopes:
            return []
        bool_query["filter"] = [{
            "bool": {"should": scopes, "minimum_should_match": 1}
        }]
    elif document_ids is not None:
        bool_query["filter"] = [{"terms": {"document_id": document_ids}}]

    response = get_client().search(
        index=settings.elasticsearch_index,
        size=max(1, top_k),
        query={"bool": bool_query},
        track_total_hits=False,
        source=True,
    )
    results = []
    for hit in response["hits"]["hits"]:
        source = dict(hit.get("_source") or {})
        result = _result_from_source(
            point_id=str(hit.get("_id") or ""),
            score=float(hit.get("_score") or 0.0),
            source=source,
        )
        result["lexical_score"] = result["score"]
        results.append(result)
    return results


def delete_by_document(document_id: int) -> int:
    return _delete_by_query({"term": {"document_id": document_id}})


def delete_by_document_version(document_id: int, doc_version: str) -> int:
    return _delete_by_query({
        "bool": {
            "must": [
                {"term": {"document_id": document_id}},
                {"term": {"doc_version": doc_version}},
            ]
        }
    })


def _delete_by_query(query: dict) -> int:
    ensure_index()
    response = get_client().delete_by_query(
        index=settings.elasticsearch_index,
        query=query,
        conflicts="proceed",
        refresh=True,
    )
    return int(response.get("deleted") or 0)


def status() -> str:
    try:
        ensure_index()
        return "connected"
    except Exception:
        return "disconnected"


def _text_mapping() -> dict:
    return {
        "type": "text",
        "analyzer": settings.elasticsearch_index_analyzer,
        "search_analyzer": settings.elasticsearch_search_analyzer,
    }


def _chunk_source(document_id: int, doc_version: str, chunk: dict) -> dict:
    return _payload_source({
        **chunk,
        "document_id": document_id,
        "doc_version": doc_version,
    })


def _payload_source(payload: dict) -> dict:
    title_path = payload.get("title_path") or []
    return {
        "document_id": payload.get("document_id"),
        "doc_version": payload.get("doc_version") or "v1",
        "chunk_id": payload.get("chunk_id"),
        "chunk_index": payload.get("chunk_index"),
        "content": payload.get("content") or "",
        "page_no": payload.get("page_no"),
        "section_title": payload.get("section_title"),
        "title_path": title_path,
        "title_path_text": payload.get("title_path_text") or " > ".join(title_path),
        "content_hash": payload.get("content_hash"),
        "token_count": payload.get("token_count", 0),
        "char_start": payload.get("char_start"),
        "char_end": payload.get("char_end"),
        "block_type": payload.get("block_type"),
        "chunk_strategy": payload.get("chunk_strategy"),
        "parser_provider": payload.get("parser_provider"),
    }


def _payload_actions(payloads: Iterable[dict]) -> Iterable[dict]:
    for payload in payloads:
        chunk_id = str(payload.get("chunk_id") or "").strip()
        document_id = payload.get("document_id")
        if not chunk_id or document_id is None or not payload.get("content"):
            continue
        yield {
            "_op_type": "index",
            "_index": settings.elasticsearch_index,
            "_id": chunk_id,
            "_source": _payload_source(payload),
        }


def _result_from_source(point_id: str, score: float, source: dict) -> dict:
    return {
        "point_id": point_id,
        "score": score,
        "document_id": source.get("document_id"),
        "doc_version": source.get("doc_version"),
        "chunk_id": source.get("chunk_id"),
        "chunk_index": source.get("chunk_index"),
        "content": source.get("content"),
        "page_no": source.get("page_no"),
        "section_title": source.get("section_title"),
        "title_path": source.get("title_path") or [],
        "title_path_text": source.get("title_path_text") or "",
        "content_hash": source.get("content_hash"),
        "token_count": source.get("token_count"),
        "char_start": source.get("char_start"),
        "char_end": source.get("char_end"),
    }
