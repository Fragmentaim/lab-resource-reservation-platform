import math
import re
from typing import List, Sequence

import httpx

from app.config import settings


_CHINESE_RE = re.compile(r"[\u4e00-\u9fff]+|[a-zA-Z0-9]+")


def rerank(question: str, results: List[dict], keywords: Sequence[str], top_k: int) -> List[dict]:
    """Rerank retrieval candidates and return the final top_k chunks."""
    if not results:
        return []

    limit = max(1, top_k)
    candidates = results[:max(limit, len(results))]
    if settings.enable_rerank:
        reranked = _rerank_by_api(question, candidates, limit)
        if reranked:
            return reranked

    return _rerank_locally(question, candidates, keywords)[:limit]


def candidate_limit(top_k: int) -> int:
    """How many first-stage retrieval candidates should be collected before rerank."""
    if not settings.enable_rerank:
        return max(1, top_k)

    multiplier = max(1, settings.rerank_candidate_multiplier)
    max_candidates = max(top_k, settings.rerank_max_candidates)
    return min(max_candidates, max(top_k, top_k * multiplier))


def is_configured() -> bool:
    return bool(settings.enable_rerank and _api_key() and _base_url() and settings.rerank_model)


def status() -> str:
    if not settings.enable_rerank:
        return "disabled"
    return "api_configured" if is_configured() else "fallback_local"


def _rerank_by_api(question: str, results: List[dict], top_k: int) -> List[dict]:
    if not is_configured():
        return []

    documents = [_document_for_rerank(result) for result in results]
    payload = {
        "model": settings.rerank_model,
        "query": question,
        "documents": documents,
        "top_n": top_k,
        "return_documents": False,
    }

    try:
        with httpx.Client(timeout=settings.rerank_timeout_seconds) as client:
            response = client.post(
                f"{_base_url()}/rerank",
                headers={"Authorization": f"Bearer {_api_key()}"},
                json=payload,
            )
            response.raise_for_status()
            data = response.json()
    except Exception as exc:
        print(f"[Rerank] API failed, fallback to local scoring: {exc}")
        return []

    ranked = []
    for item in data.get("results", []):
        index = item.get("index")
        if index is None or index < 0 or index >= len(results):
            continue
        rerank_score = item.get("relevance_score", item.get("score", 0.0))
        result = dict(results[index])
        result["retrieval_score"] = result.get("score")
        result["rerank_score"] = float(rerank_score or 0.0)
        result["score"] = result["rerank_score"]
        result["rerank_provider"] = "api"
        ranked.append(result)

    return ranked[:top_k]


def _rerank_locally(question: str, results: List[dict], keywords: Sequence[str]) -> List[dict]:
    query_terms = _terms(question)
    keyword_set = {kw.lower() for kw in keywords if kw}

    ranked = []
    for index, result in enumerate(results):
        content = _document_for_rerank(result).lower()
        content_terms = set(_terms(content))
        overlap = len(query_terms & content_terms)
        keyword_hits = sum(1 for keyword in keyword_set if keyword and keyword in content)
        title_bonus = _title_bonus(result, query_terms, keyword_set)
        retrieval_score = float(result.get("score") or 0.0)

        local_score = (
            math.log1p(max(retrieval_score, 0.0))
            + overlap * 0.25
            + keyword_hits * 0.18
            + title_bonus
        )
        reranked = dict(result)
        reranked["retrieval_score"] = retrieval_score
        reranked["rerank_score"] = local_score
        reranked["score"] = local_score
        reranked["rerank_provider"] = "local"
        ranked.append((local_score, -index, reranked))

    ranked.sort(key=lambda item: (item[0], item[1]), reverse=True)
    return [item[2] for item in ranked]


def _document_for_rerank(result: dict) -> str:
    parts = []
    title_path = result.get("title_path_text")
    if not title_path and result.get("title_path"):
        title_path = " > ".join(result.get("title_path") or [])
    if title_path:
        parts.append(f"章节路径：{title_path}")
    if result.get("section_title"):
        parts.append(f"章节标题：{result['section_title']}")
    if result.get("content"):
        parts.append(str(result["content"]))

    text = "\n".join(parts)
    max_chars = max(200, settings.rerank_document_max_chars)
    return text[:max_chars]


def _terms(text: str) -> set[str]:
    terms = set()
    for token in _CHINESE_RE.findall(text.lower()):
        if not token:
            continue
        terms.add(token)
        if _is_chinese(token) and len(token) >= 4:
            terms.update(token[i:i + 2] for i in range(len(token) - 1))
    return terms


def _title_bonus(result: dict, query_terms: set[str], keywords: set[str]) -> float:
    title = " ".join([
        str(result.get("section_title") or ""),
        str(result.get("title_path_text") or ""),
    ]).lower()
    if not title:
        return 0.0
    return sum(0.12 for term in query_terms if term in title) + sum(0.08 for kw in keywords if kw in title)


def _is_chinese(text: str) -> bool:
    return any("\u4e00" <= char <= "\u9fff" for char in text)


def _base_url() -> str:
    base_url = (settings.rerank_base_url or settings.embedding_base_url or settings.llm_base_url).rstrip("/")
    if base_url.endswith("/v1"):
        return base_url
    return f"{base_url}/v1"


def _api_key() -> str:
    return settings.rerank_api_key or settings.embedding_api_key or settings.llm_api_key
