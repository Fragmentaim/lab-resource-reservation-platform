from pathlib import Path
from dataclasses import dataclass
from qdrant_client import QdrantClient
from qdrant_client.models import (
    Distance, VectorParams, PointStruct,
    Filter, FieldCondition, MatchValue, MatchAny,
    DeletePayload, PointsSelector, PointIdsList,
)
from typing import List, Optional
import re
import threading
import uuid
from rank_bm25 import BM25Okapi
from app.config import settings


_client = None
_bm25_index = None
_bm25_lock = threading.Lock()
# Embedded Qdrant writes are serialized; reads remain concurrent.
_mutation_lock = threading.RLock()


@dataclass(frozen=True)
class _Bm25Index:
    """A process-local lexical index rebuilt lazily from the active collection."""

    corpus: list[dict]
    scorer: Optional[BM25Okapi]


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
        invalidate_bm25_index()

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


def search_by_bm25(query: str, top_k: int = None, document_ids: Optional[List[int]] = None) -> list:
    """Run BM25 over active chunks after applying the document ACL filter."""
    if document_ids is not None and not document_ids:
        return []

    k = top_k or settings.top_k
    query_tokens = _bm25_tokens(query)
    if not query_tokens:
        return []

    index = _get_bm25_index()
    if not index.scorer:
        return []
    allowed_ids = set(document_ids) if document_ids is not None else None
    scores = index.scorer.get_scores(query_tokens)
    ranked = []
    for corpus_index, (payload, score) in enumerate(zip(index.corpus, scores)):
        if allowed_ids is not None and payload.get("document_id") not in allowed_ids:
            continue
        if float(score) <= 0:
            continue
        result = _result_from_payload(str(payload.get("point_id") or ""), float(score), payload)
        result["lexical_score"] = float(score)
        result["bm25_corpus_index"] = corpus_index
        ranked.append(result)
    ranked.sort(key=lambda item: (-item["score"], _stable_chunk_key(item)))
    return ranked[:k]


def search_by_keywords(keywords: list, top_k: int = None, document_ids: Optional[List[int]] = None) -> list:
    return search_by_bm25(" ".join(str(keyword) for keyword in keywords if keyword), top_k, document_ids)


def invalidate_bm25_index() -> None:
    global _bm25_index
    with _bm25_lock:
        _bm25_index = None


def _get_bm25_index() -> _Bm25Index:
    global _bm25_index
    if _bm25_index is not None:
        return _bm25_index

    with _bm25_lock:
        if _bm25_index is None:
            corpus = _load_bm25_corpus()
            tokenized = [_bm25_tokens(_bm25_document_text(payload)) for payload in corpus]
            # BM25Okapi requires at least one token per document.
            _bm25_index = _Bm25Index(
                corpus=corpus,
                scorer=BM25Okapi(tokenized, k1=settings.bm25_k1, b=settings.bm25_b) if tokenized else None,
            )
    return _bm25_index


def _load_bm25_corpus() -> list[dict]:
    client = get_client()
    corpus = []
    offset = None
    while True:
        points, offset = client.scroll(
            collection_name=settings.qdrant_collection,
            offset=offset,
            limit=512,
            with_payload=True,
        )
        for point in points:
            payload = dict(point.payload or {})
            if not payload.get("content"):
                continue
            payload["point_id"] = str(point.id)
            corpus.append(payload)
        if offset is None:
            break
    return corpus


def _bm25_document_text(payload: dict) -> str:
    title_path = payload.get("title_path_text") or " ".join(payload.get("title_path") or [])
    return "\n".join(filter(None, [
        str(title_path),
        str(payload.get("section_title") or ""),
        str(payload.get("content") or ""),
    ]))


def _bm25_tokens(text: str) -> list[str]:
    """Tokenize ASCII terms and Chinese unigrams/bigrams for BM25."""
    tokens = []
    for part in re.findall(r"[\u4e00-\u9fff]+|[A-Za-z0-9_.%+-]+", (text or "").lower()):
        if any("\u4e00" <= char <= "\u9fff" for char in part):
            tokens.append(part)
            if len(part) >= 2:
                tokens.extend(part[index:index + 2] for index in range(len(part) - 1))
        else:
            tokens.append(part)
    return tokens


def _stable_chunk_key(result: dict) -> str:
    return str(result.get("chunk_id") or result.get("content_hash") or result.get("point_id") or "")


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
        invalidate_bm25_index()

    return len(ids)
