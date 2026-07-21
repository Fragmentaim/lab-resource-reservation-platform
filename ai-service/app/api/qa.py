from fastapi import APIRouter
from starlette.concurrency import run_in_threadpool
from app.models.schemas import (
    KnowledgeCandidate, KnowledgeChunk, KnowledgeOpenChunksRequest, KnowledgeOpenChunksResponse,
    KnowledgeSearchRequest, KnowledgeSearchResponse,
    SessionSummaryRequest, SessionSummaryResponse,
)
from app.core import rag_pipeline, session_summarizer, vectorstore

router = APIRouter(prefix="/qa", tags=["qa"])


@router.post("/retrieve", response_model=KnowledgeSearchResponse)
async def retrieve_candidates(request: KnowledgeSearchRequest):
    """Return ACL-scoped candidate locators only; this endpoint never calls the chat model."""
    results = await run_in_threadpool(
        rag_pipeline.retrieve_candidates,
        question=request.question,
        document_ids=request.document_ids,
        top_k=request.top_k,
        score_threshold=request.score_threshold,
    )
    return KnowledgeSearchResponse(
        query=request.question,
        candidates=[KnowledgeCandidate(
            chunk_uid=result["chunk_id"],
            document_id=result["document_id"],
            doc_version=result.get("doc_version"),
            chunk_index=result.get("chunk_index"),
            page_no=result.get("page_no"),
            section_title=result.get("section_title"),
            title_path=result.get("title_path") or [],
            content_hash=result.get("content_hash"),
            token_count=result.get("token_count"),
            score=result.get("score") or 0.0,
            retrieval_score=result.get("retrieval_score"),
            rerank_score=result.get("rerank_score"),
            rerank_provider=result.get("rerank_provider"),
            retrieval_source=result.get("retrieval_source"),
            locator=(result.get("content") or "")[:200],
        ) for result in results if result.get("chunk_id") and result.get("document_id") is not None],
    )


@router.post("/chunks/open", response_model=KnowledgeOpenChunksResponse)
async def open_chunks(request: KnowledgeOpenChunksRequest):
    """Load complete chunks only when their document remains inside Java's ACL allow-list."""
    results = await run_in_threadpool(
        vectorstore.get_chunks_by_ids,
        chunk_ids=request.chunk_uids,
        document_ids=request.document_ids,
    )
    return KnowledgeOpenChunksResponse(chunks=[KnowledgeChunk(
        chunk_uid=result["chunk_id"],
        document_id=result["document_id"],
        doc_version=result.get("doc_version"),
        chunk_index=result.get("chunk_index"),
        page_no=result.get("page_no"),
        section_title=result.get("section_title"),
        title_path=result.get("title_path") or [],
        content_hash=result.get("content_hash"),
        token_count=result.get("token_count"),
        content=result.get("content") or "",
    ) for result in results if result.get("chunk_id") and result.get("document_id") is not None])


@router.post("/sessions/summarize", response_model=SessionSummaryResponse)
async def summarize_session(request: SessionSummaryRequest):
    turns = [msg.model_dump() for msg in request.new_turns]
    result = await run_in_threadpool(
        session_summarizer.summarize_session,
        existing_summary=request.existing_summary,
        new_turns=turns,
        max_summary_tokens=request.max_summary_tokens,
    )
    return SessionSummaryResponse(**result)
