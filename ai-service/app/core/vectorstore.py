from pathlib import Path
from qdrant_client import QdrantClient
from qdrant_client.models import (
    Distance, VectorParams, PointStruct,
    Filter, FieldCondition, MatchValue, MatchAny,
    DeletePayload, PointsSelector, PointIdsList,
)
from typing import List, Optional
import uuid
import tempfile
from app.config import settings


_client = None


def get_client() -> QdrantClient:
    global _client
    if _client is None:
        try:
            remote_client = QdrantClient(url=settings.qdrant_url)
            remote_client.get_collections()
            _client = remote_client
        except Exception:
            # 使用临时目录避免锁定问题
            local_path = Path(tempfile.gettempdir()) / "qdrant_lab_knowledge"
            local_path.mkdir(exist_ok=True)
            _client = QdrantClient(path=str(local_path))
    return _client


def ensure_collection():
    """Create the collection if it doesn't exist."""
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
                "source_location": chunk.get("source_location"),
                "parser_provider": chunk.get("parser_provider"),
            },
        ))

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
            score_threshold=score_threshold or settings.score_threshold,
            with_payload=True,
        )
    else:
        response = client.query_points(
            collection_name=settings.qdrant_collection,
            query=query_vector,
            query_filter=query_filter,
            limit=top_k or settings.top_k,
            score_threshold=score_threshold or settings.score_threshold,
            with_payload=True,
        )
        results = response.points

    return [
        _result_from_payload(str(r.id), r.score, r.payload or {})
        for r in results
    ]


def search_by_keywords(keywords: list, top_k: int = None, document_ids: Optional[List[int]] = None) -> list:
    """Keyword search with simple scoring and ranking."""
    if document_ids is not None and not document_ids:
        return []

    client = get_client()
    k = top_k or settings.top_k
    normalized_keywords = list(dict.fromkeys(kw.strip() for kw in keywords if kw and kw.strip()))
    if not normalized_keywords:
        return []

    scroll_filter = None
    if document_ids is not None:
        scroll_filter = Filter(
            must=[FieldCondition(key="document_id", match=MatchAny(any=document_ids))]
        )

    points, _ = client.scroll(
        collection_name=settings.qdrant_collection,
        scroll_filter=scroll_filter,
        limit=10000,
        with_payload=True,
    )

    results = []
    for p in points:
        payload = p.payload or {}
        content = payload.get("content", "")
        if not content:
            continue

        content_for_match = content.lower()
        score = 0.0
        matched_keywords = 0
        for kw in normalized_keywords:
            keyword_for_match = kw.lower()
            occurrences = content_for_match.count(keyword_for_match)
            if occurrences > 0:
                matched_keywords += 1
                score += occurrences * (3.0 if len(kw) >= 3 else 1.0)

        if matched_keywords == 0:
            continue

        score += min(len(content), 400) / 1000.0
        results.append({
            "point_id": str(p.id),
            "score": score,
            "document_id": payload.get("document_id"),
            "doc_version": payload.get("doc_version"),
            "chunk_id": payload.get("chunk_id"),
            "chunk_index": payload.get("chunk_index"),
            "content": content,
            "page_no": payload.get("page_no"),
            "section_title": payload.get("section_title"),
            "title_path": payload.get("title_path") or [],
            "title_path_text": payload.get("title_path_text") or "",
            "content_hash": payload.get("content_hash"),
            "token_count": payload.get("token_count"),
            "char_start": payload.get("char_start"),
            "char_end": payload.get("char_end"),
            "matched_keywords": matched_keywords,
        })

    results.sort(key=lambda item: (item["matched_keywords"], item["score"]), reverse=True)
    return results[:k]


def get_chunks_by_ids(chunk_ids: List[str], document_ids: Optional[List[int]] = None) -> List[dict]:
    """Load complete chunks by their stable chunk IDs, respecting the document filter."""
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
    """Delete all vectors belonging to a document."""
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
