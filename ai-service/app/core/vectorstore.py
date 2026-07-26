from pathlib import Path
from qdrant_client import QdrantClient
from qdrant_client.models import (
    Distance, VectorParams, PointStruct,
    Filter, FieldCondition, MatchValue, MatchAny,
    PointIdsList,
)
from typing import Iterator, List, Optional, Sequence
import threading
import uuid
from app.config import settings


_client = None
# Embedded Qdrant writes are serialized; reads remain concurrent.
_mutation_lock = threading.RLock()


def get_client() -> QdrantClient:
    global _client
    if _client is None:
        if settings.qdrant_local_path:
            local_path = Path(settings.qdrant_local_path)
            local_path.mkdir(parents=True, exist_ok=True)
            _client = QdrantClient(path=str(local_path))
        else:
            if not settings.qdrant_url:
                raise RuntimeError("QDRANT_URL is required when QDRANT_LOCAL_PATH is not configured")
            remote_client = QdrantClient(url=settings.qdrant_url)
            remote_client.get_collections()
            _client = remote_client
    return _client


def ensure_collection():
    client = get_client()
    collections = client.get_collections().collections
    names = [c.name for c in collections]

    if settings.qdrant_collection not in names:
        client.create_collection(
            collection_name=settings.qdrant_collection,
            vectors_config=VectorParams(
                size=settings.qdrant_vector_size,
                distance=Distance.COSINE,
            ),
        )


def upsert_chunks(
    document_id: int,
    chunks: List[dict],
    vectors: List[List[float]],
    doc_version: str = "v1",
) -> List[str]:
    """Insert chunk vectors with metadata into Qdrant."""
    client = get_client()
    points = []

    for i, (chunk, vector) in enumerate(zip(chunks, vectors)):
        chunk_id = chunk.get("chunk_id") or _make_chunk_id(document_id, doc_version, chunk, i)
        chunk["chunk_id"] = chunk_id
        point_id = str(uuid.uuid5(uuid.NAMESPACE_URL, chunk_id))
        points.append(PointStruct(
            id=point_id,
            vector=vector,
            payload={
                "document_id": document_id,
                "doc_version": doc_version,
                "chunk_id": chunk_id,
                "chunk_index": chunk.get("chunk_index", i),
                "content": chunk["content"],
                "page_no": chunk.get("page_no"),
                "section_title": chunk.get("section_title"),
                "title_path": chunk.get("title_path") or [],
                "title_path_text": chunk.get("title_path_text") or "",
                "content_hash": chunk.get("content_hash"),
                "token_count": chunk.get("token_count", 0),
                "char_start": chunk.get("char_start"),
                "char_end": chunk.get("char_end"),
                "block_type": chunk.get("block_type"),
                "chunk_strategy": chunk.get("chunk_strategy"),
                "source_location": chunk.get("source_location"),
                "parser_provider": chunk.get("parser_provider"),
            },
        ))

    with _mutation_lock:
        client.upsert(
            collection_name=settings.qdrant_collection,
            points=points,
        )

    return [p.id for p in points]


def _make_chunk_id(document_id: int, doc_version: str, chunk: dict, chunk_index: int) -> str:
    content_hash = (chunk.get("content_hash") or "").strip()
    if not content_hash:
        content_hash = uuid.uuid5(uuid.NAMESPACE_URL, chunk["content"]).hex
    return f"doc-{document_id}-{doc_version}-chunk-{chunk_index:04d}-{content_hash[:12]}"


def search(
    query_vector: List[float],
    top_k: Optional[int] = None,
    score_threshold: Optional[float] = None,
    document_ids: Optional[List[int]] = None,
) -> List[dict]:
    """Search for similar vectors and return scored results."""
    client = get_client()

    query_filter = None
    if document_ids is not None:
        if not document_ids:
            return []
        query_filter = Filter(
            must=[FieldCondition(
                key="document_id",
                match=MatchAny(any=document_ids),
            )]
        )

    results = []
    if hasattr(client, "search"):
        results = client.search(
            collection_name=settings.qdrant_collection,
            query_vector=query_vector,
            query_filter=query_filter,
            limit=top_k or settings.top_k,
            score_threshold=settings.score_threshold if score_threshold is None else score_threshold,
            with_payload=True,
        )
    else:
        response = client.query_points(
            collection_name=settings.qdrant_collection,
            query=query_vector,
            query_filter=query_filter,
            limit=top_k or settings.top_k,
            score_threshold=settings.score_threshold if score_threshold is None else score_threshold,
            with_payload=True,
        )
        results = response.points

    return [
        _result_from_payload(str(r.id), r.score, r.payload or {})
        for r in results
    ]


def search_by_keywords(
    keywords: str | Sequence[str],
    top_k: int = None,
    document_ids: Optional[List[int]] = None,
) -> list:
    """Search the Elasticsearch BM25 index with the caller's ACL document IDs."""
    query = keywords if isinstance(keywords, str) else " ".join(
        str(keyword) for keyword in keywords if keyword
    )
    from app.core import elasticsearch_store
    return elasticsearch_store.search(
        query=query,
        top_k=top_k or settings.top_k,
        document_ids=document_ids,
    )


def iter_chunk_payloads(batch_size: int = 512) -> Iterator[dict]:
    """Stream existing Qdrant chunk payloads for lexical-index migration."""
    client = get_client()
    offset = None
    while True:
        points, offset = client.scroll(
            collection_name=settings.qdrant_collection,
            offset=offset,
            limit=max(1, batch_size),
            with_payload=True,
        )
        for point in points:
            payload = dict(point.payload or {})
            if not payload.get("content"):
                continue
            payload["point_id"] = str(point.id)
            yield payload
        if offset is None:
            break


def get_chunks_by_ids(chunk_ids: List[str], document_ids: Optional[List[int]] = None) -> List[dict]:
    """Load chunks by ID after applying the document filter."""
    if not chunk_ids or document_ids is not None and not document_ids:
        return []

    client = get_client()
    conditions = [FieldCondition(key="chunk_id", match=MatchAny(any=chunk_ids))]
    if document_ids is not None:
        conditions.append(FieldCondition(key="document_id", match=MatchAny(any=document_ids)))
    points, _ = client.scroll(
        collection_name=settings.qdrant_collection,
        scroll_filter=Filter(must=conditions),
        limit=len(chunk_ids),
        with_payload=True,
    )
    loaded = {
        (point.payload or {}).get("chunk_id"): _result_from_payload(str(point.id), 0.0, point.payload or {})
        for point in points
    }
    # Preserve the caller's selection order. Missing or unauthorized IDs are omitted.
    return [loaded[chunk_id] for chunk_id in chunk_ids if chunk_id in loaded]


def _result_from_payload(point_id: str, score: float, payload: dict) -> dict:
    return {
        "point_id": point_id,
        "score": score,
        "document_id": payload.get("document_id"),
        "doc_version": payload.get("doc_version"),
        "chunk_id": payload.get("chunk_id"),
        "chunk_index": payload.get("chunk_index"),
        "content": payload.get("content"),
        "page_no": payload.get("page_no"),
        "section_title": payload.get("section_title"),
        "title_path": payload.get("title_path") or [],
        "title_path_text": payload.get("title_path_text") or "",
        "content_hash": payload.get("content_hash"),
        "token_count": payload.get("token_count"),
        "char_start": payload.get("char_start"),
        "char_end": payload.get("char_end"),
    }


def delete_by_document(document_id: int) -> int:
    with _mutation_lock:
        client = get_client()
        points, _ = client.scroll(
            collection_name=settings.qdrant_collection,
            scroll_filter=Filter(
                must=[FieldCondition(
                    key="document_id",
                    match=MatchValue(value=document_id),
                )]
            ),
            limit=10000,
            with_payload=False,
        )
        if not points:
            return 0
        ids = [p.id for p in points]
        client.delete(
            collection_name=settings.qdrant_collection,
            points_selector=PointIdsList(points=ids),
        )

    return len(ids)
