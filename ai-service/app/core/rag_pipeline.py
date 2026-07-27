"""ACL-scoped hybrid retrieval used by the Agent knowledge tools."""

import re

from typing import List, Optional
from app.core import embedder, vectorstore, reranker
from app.config import settings


def _extract_keywords(question: str) -> list:
    """Extract meaningful keywords from a Chinese question."""
    stopwords = {"的", "了", "是", "在", "有", "和", "与", "或", "不", "也", "都",
                 "吗", "呢", "啊", "吧", "什么", "怎么", "如何", "哪些", "哪个",
                 "为什么", "能", "可以", "会", "请", "问", "一下", "告诉", "我",
                 "你", "他", "她", "它", "这", "那", "被", "把", "给", "从",
                 "到", "对", "向", "以", "及", "等", "很", "最", "比较"}
    tokens = re.findall(r'[\u4e00-\u9fff]+|[a-zA-Z0-9]+', question)
    keywords = []
    for t in tokens:
        if t not in stopwords:
            keywords.append(t)
        if len(t) >= 4:
            for i in range(len(t) - 1):
                keywords.append(t[i:i+2])
    return list(dict.fromkeys(keywords)) or tokens


def retrieve_candidates(
    question: str,
    document_ids: Optional[List[int]] = None,
    top_k: Optional[int] = None,
    score_threshold: Optional[float] = None,
    apply_rerank: bool = True,
    document_versions: Optional[dict[int, str]] = None,
) -> List[dict]:
    """Retrieve and rerank chunks without invoking a chat model or assembling an answer."""
    return _retrieve_ranked_candidates(
        question,
        document_ids,
        top_k,
        score_threshold,
        apply_rerank,
        document_versions,
    )


def _retrieve_ranked_candidates(
    question: str,
    document_ids: Optional[List[int]],
    top_k: Optional[int],
    score_threshold: Optional[float],
    apply_rerank: bool = True,
    document_versions: Optional[dict[int, str]] = None,
) -> List[dict]:
    """Collect lexical and vector candidates, fuse them, then rerank once."""
    k = top_k or settings.top_k
    candidate_k = reranker.candidate_limit(k)
    threshold = settings.score_threshold if score_threshold is None else score_threshold
    vector_results: List[dict] = []
    keyword_results: List[dict] = []

    if settings.retrieval_mode == "vector":
        vector_results = vectorstore.search(
            query_vector=embedder.embed_query(question),
            top_k=candidate_k,
            score_threshold=threshold,
            document_ids=document_ids,
            document_versions=document_versions,
        )
        _mark_retrieval_source(vector_results, "vector")
        if settings.enable_hybrid_search or not vector_results:
            keyword_results = vectorstore.search_by_keywords(
                question,
                top_k=candidate_k,
                document_ids=document_ids,
                document_versions=document_versions,
            )
            _mark_retrieval_source(keyword_results, "bm25")
    else:
        keyword_results = vectorstore.search_by_keywords(
            question,
            top_k=candidate_k,
            document_ids=document_ids,
            document_versions=document_versions,
        )
        _mark_retrieval_source(keyword_results, "bm25")
        if (settings.enable_hybrid_search or not keyword_results) and settings.enable_embedding:
            vector_results = vectorstore.search(
                query_vector=embedder.embed_query(question),
                top_k=candidate_k,
                score_threshold=threshold,
                document_ids=document_ids,
                document_versions=document_versions,
            )
            _mark_retrieval_source(vector_results, "vector")

    if settings.enable_hybrid_search and vector_results and keyword_results:
        first_stage = _fuse_hybrid_results(vector_results, keyword_results, candidate_k)
    else:
        first_stage = _dedupe_results(vector_results + keyword_results)
    if not apply_rerank:
        return first_stage[:min(len(first_stage), candidate_k)]
    return reranker.rerank(question, first_stage, _extract_keywords(question), k)


def _fuse_hybrid_results(vector_results: List[dict], keyword_results: List[dict], limit: int) -> List[dict]:
    """Fuse incomparable vector/lexical scores with deterministic weighted RRF."""
    rrf_k = max(1, settings.hybrid_rrf_k)
    channels = (
        ("vector", vector_results, max(0.0, settings.hybrid_vector_weight)),
        ("bm25", keyword_results, max(0.0, settings.hybrid_keyword_weight)),
    )
    merged: dict[str, dict] = {}
    for source, results, weight in channels:
        for rank, raw in enumerate(results, start=1):
            key = _result_key(raw)
            if not key:
                continue
            current = merged.setdefault(key, {"result": dict(raw), "score": 0.0, "ranks": {}, "sources": set()})
            current["score"] += weight / (rrf_k + rank)
            current["ranks"][source] = rank
            current["sources"].add(source)

    fused = []
    for item in merged.values():
        result = item["result"]
        result["retrieval_source"] = ",".join(sorted(item["sources"]))
        result["retrieval_score"] = item["score"]
        result["fusion_score"] = item["score"]
        result["fusion_method"] = "weighted_rrf"
        result["retrieval_ranks"] = item["ranks"]
        fused.append(result)
    fused.sort(key=lambda item: (-item["fusion_score"], _result_key(item)))
    return fused[:max(1, limit)]


def _dedupe_results(results: List[dict]) -> List[dict]:
    seen = {}
    deduped = []
    for result in results:
        key = _result_key(result)
        if key in seen:
            existing = seen[key]
            existing_sources = set(str(existing.get("retrieval_source") or "").split(","))
            new_sources = set(str(result.get("retrieval_source") or "").split(","))
            sources = sorted(source for source in existing_sources | new_sources if source)
            if sources:
                existing["retrieval_source"] = ",".join(sources)
            if (result.get("score") or 0) > (existing.get("score") or 0):
                existing["score"] = result.get("score")
            continue
        seen[key] = result
        deduped.append(result)
    return deduped


def _result_key(result: dict) -> str:
    return str(result.get("chunk_id") or result.get("content_hash") or result.get("content") or "")


def _mark_retrieval_source(results: List[dict], source: str) -> None:
    for result in results:
        result["retrieval_source"] = source
