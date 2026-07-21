import re

from typing import Iterator, List, Optional
from app.core import embedder, vectorstore, llm, reranker, query_rewriter, context_assembler
from app.config import settings
import time


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


def answer_question(
    question: str,
    document_ids: Optional[List[int]] = None,
    chat_history: Optional[List[dict]] = None,
    session_summary: Optional[str] = None,
    context_options: Optional[dict] = None,
    top_k: Optional[int] = None,
    score_threshold: Optional[float] = None,
    model: Optional[str] = None,
) -> dict:
    """Full RAG pipeline: retrieve -> assemble prompt -> generate answer."""
    start = time.time()
    system_prompt, user_prompt, packed_history, sources, rewritten_question, rewrite_applied, context_stats = _build_rag_prompt(
        question=question,
        document_ids=document_ids,
        chat_history=chat_history,
        session_summary=session_summary,
        context_options=context_options,
        top_k=top_k,
        score_threshold=score_threshold,
        model=model,
    )

    answer, provider_usage = llm.chat_with_usage(
        system_prompt=system_prompt,
        user_message=user_prompt,
        history=packed_history,
        model=model,
    )

    latency_ms = int((time.time() - start) * 1000)
    context_stats["provider_usage"] = provider_usage

    return {
        "answer": answer,
        "sources": sources,
        "latency_ms": latency_ms,
        "model": model or settings.chat_model,
        "rewritten_question": rewritten_question,
        "rewrite_applied": rewrite_applied,
        "context_stats": context_stats,
    }


def retrieve_candidates(
    question: str,
    document_ids: Optional[List[int]] = None,
    top_k: Optional[int] = None,
    score_threshold: Optional[float] = None,
) -> List[dict]:
    """Retrieve and rerank chunks without invoking a chat model or assembling an answer."""
    k = top_k or settings.top_k
    threshold = score_threshold or settings.score_threshold
    keywords = _extract_keywords(question)
    candidate_k = reranker.candidate_limit(k)

    if settings.retrieval_mode == "vector":
        query_vector = embedder.embed_query(question)
        search_results = vectorstore.search(
            query_vector=query_vector,
            top_k=candidate_k,
            score_threshold=threshold,
            document_ids=document_ids,
        )
        _mark_retrieval_source(search_results, "vector")
        if settings.enable_hybrid_search:
            keyword_results = vectorstore.search_by_keywords(keywords, top_k=candidate_k, document_ids=document_ids)
            _mark_retrieval_source(keyword_results, "keyword")
            search_results.extend(keyword_results)
        if not search_results:
            search_results = vectorstore.search_by_keywords(keywords, top_k=k, document_ids=document_ids)
            _mark_retrieval_source(search_results, "keyword")
    else:
        search_results = vectorstore.search_by_keywords(keywords, top_k=candidate_k, document_ids=document_ids)
        _mark_retrieval_source(search_results, "keyword")
        if (settings.enable_hybrid_search or not search_results) and settings.enable_embedding:
            query_vector = embedder.embed_query(question)
            vector_results = vectorstore.search(
                query_vector=query_vector,
                top_k=candidate_k,
                score_threshold=threshold,
                document_ids=document_ids,
            )
            _mark_retrieval_source(vector_results, "vector")
            search_results.extend(vector_results)

    search_results = _dedupe_results(search_results)
    ranked_limit = min(len(search_results), max(k, candidate_k))
    return reranker.rerank(question, search_results, keywords, ranked_limit)


def answer_question_stream(
    question: str,
    document_ids: Optional[List[int]] = None,
    chat_history: Optional[List[dict]] = None,
    session_summary: Optional[str] = None,
    context_options: Optional[dict] = None,
    top_k: Optional[int] = None,
    score_threshold: Optional[float] = None,
    model: Optional[str] = None,
) -> Iterator[dict]:
    """Streaming RAG pipeline: emit sources first, then token deltas."""
    start = time.time()
    system_prompt, user_prompt, packed_history, sources, rewritten_question, rewrite_applied, context_stats = _build_rag_prompt(
        question=question,
        document_ids=document_ids,
        chat_history=chat_history,
        session_summary=session_summary,
        context_options=context_options,
        top_k=top_k,
        score_threshold=score_threshold,
        model=model,
    )
    resolved_model = model or settings.chat_model

    yield {
        "type": "meta",
        "sources": sources,
        "model": resolved_model,
        "rewritten_question": rewritten_question,
        "rewrite_applied": rewrite_applied,
        "context_stats": context_stats,
    }

    for content in llm.chat_stream(
        system_prompt=system_prompt,
        user_message=user_prompt,
        history=packed_history,
        model=model,
    ):
        yield {
            "type": "delta",
            "content": content,
        }

    yield {
        "type": "done",
        "latency_ms": int((time.time() - start) * 1000),
        "model": resolved_model,
    }


def _build_rag_prompt(
    question: str,
    document_ids: Optional[List[int]] = None,
    chat_history: Optional[List[dict]] = None,
    session_summary: Optional[str] = None,
    context_options: Optional[dict] = None,
    top_k: Optional[int] = None,
    score_threshold: Optional[float] = None,
    model: Optional[str] = None,
) -> tuple[str, str, list, list, str, bool, dict]:
    k = top_k or settings.top_k
    threshold = score_threshold or settings.score_threshold
    rewritten_question, rewrite_applied = query_rewriter.rewrite_question(
        question=question,
        session_summary=session_summary,
        chat_history=chat_history,
        model=model,
    )
    retrieval_question = rewritten_question or question
    keywords = _extract_keywords(retrieval_question)
    candidate_k = reranker.candidate_limit(k)

    if settings.retrieval_mode == "vector":
        query_vector = embedder.embed_query(retrieval_question)
        search_results = vectorstore.search(
            query_vector=query_vector,
            top_k=candidate_k,
            score_threshold=threshold,
            document_ids=document_ids,
        )
        _mark_retrieval_source(search_results, "vector")
        if settings.enable_hybrid_search:
            keyword_results = vectorstore.search_by_keywords(
                keywords,
                top_k=candidate_k,
                document_ids=document_ids,
            )
            _mark_retrieval_source(keyword_results, "keyword")
            search_results.extend(keyword_results)
        if not search_results:
            search_results = vectorstore.search_by_keywords(keywords, top_k=k, document_ids=document_ids)
            _mark_retrieval_source(search_results, "keyword")
    else:
        search_results = vectorstore.search_by_keywords(keywords, top_k=candidate_k, document_ids=document_ids)
        _mark_retrieval_source(search_results, "keyword")
        if (settings.enable_hybrid_search or not search_results) and settings.enable_embedding:
            query_vector = embedder.embed_query(retrieval_question)
            vector_results = vectorstore.search(
                query_vector=query_vector,
                top_k=candidate_k,
                score_threshold=threshold,
                document_ids=document_ids,
            )
            _mark_retrieval_source(vector_results, "vector")
            search_results.extend(vector_results)

    search_results = _dedupe_results(search_results)
    ranked_limit = min(len(search_results), max(k, candidate_k))
    search_results = reranker.rerank(retrieval_question, search_results, keywords, ranked_limit)
    assembled = context_assembler.assemble_context(
        original_question=question,
        rewritten_question=retrieval_question,
        ranked_results=search_results,
        session_summary=session_summary,
        chat_history=chat_history,
        top_k=k,
        context_options=context_options,
    )
    sources = []
    for result in assembled["sources"]:
        sources.append({
            "document_id": result["document_id"],
            "doc_version": result.get("doc_version"),
            "chunk_id": result.get("chunk_id"),
            "chunk_index": result.get("chunk_index"),
            "page_no": result.get("page_no"),
            "section_title": result.get("section_title"),
            "title_path": result.get("title_path") or [],
            "content_hash": result.get("content_hash"),
            "score": result["score"],
            "retrieval_score": result.get("retrieval_score"),
            "rerank_score": result.get("rerank_score"),
            "rerank_provider": result.get("rerank_provider"),
            "retrieval_source": result.get("retrieval_source"),
            "excerpt": result["content"][:200],
        })

    return (
        assembled["system_prompt"],
        assembled["user_prompt"],
        assembled["history"],
        sources,
        retrieval_question,
        rewrite_applied,
        assembled["context_stats"],
    )


def _dedupe_results(results: List[dict]) -> List[dict]:
    seen = {}
    deduped = []
    for result in results:
        key = result.get("chunk_id") or result.get("content_hash") or result.get("content")
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


def _mark_retrieval_source(results: List[dict], source: str) -> None:
    for result in results:
        result["retrieval_source"] = source


def _format_source_header(index: int, result: dict) -> str:
    parts = [
        f"文档ID: {result.get('document_id')}",
        f"页码: {result.get('page_no', 'N/A')}",
    ]
    if result.get("section_title"):
        parts.append(f"章节: {result['section_title']}")
    if result.get("chunk_id"):
        parts.append(f"chunkId: {result['chunk_id']}")
    return f"[来源 {index}] ({', '.join(parts)})"
