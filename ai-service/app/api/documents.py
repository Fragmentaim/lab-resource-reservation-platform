from datetime import datetime, timezone
from threading import Lock
from uuid import uuid4

from fastapi import APIRouter, UploadFile, File, Form, BackgroundTasks, HTTPException
from starlette.concurrency import run_in_threadpool
from app.models.schemas import (
    ChunkResult,
    ProcessResponse,
    ProcessTaskResponse,
    DeleteVectorsResponse,
)
from app.core import parser, chunker, embedder, vectorstore, elasticsearch_store
from app.config import settings
import tempfile
import os

router = APIRouter(prefix="/documents", tags=["documents"])
_tasks = {}
_task_lock = Lock()


@router.post("/process", response_model=ProcessResponse)
async def process_document(
    document_id: int = Form(...),
    file_type: str = Form(...),
    doc_version: str = Form("v1"),
    file: UploadFile = File(...),
):
    """Receive a document file, parse, chunk, and store retrievable data."""
    tmp_path = await _save_upload(file, file_type)

    try:
        return await run_in_threadpool(
            _process_file,
            document_id,
            file_type,
            doc_version,
            tmp_path,
        )
    finally:
        os.unlink(tmp_path)


@router.post("/process-async", response_model=ProcessTaskResponse)
async def process_document_async(
    background_tasks: BackgroundTasks,
    document_id: int = Form(...),
    file_type: str = Form(...),
    doc_version: str = Form("v1"),
    file: UploadFile = File(...),
):
    """Start document processing in the background and return a task id."""
    tmp_path = await _save_upload(file, file_type)
    task_id = _create_task(document_id, doc_version)
    background_tasks.add_task(
        _run_process_task,
        task_id,
        document_id,
        file_type,
        doc_version,
        tmp_path,
    )
    return _get_task_response(task_id)


@router.get("/tasks/{task_id}", response_model=ProcessTaskResponse)
async def get_process_task(task_id: str):
    """Get background document processing task status."""
    return _get_task_response(task_id)


@router.delete("/{document_id}/vectors", response_model=DeleteVectorsResponse)
async def delete_document_vectors(document_id: int):
    """Delete all dense and lexical indexes for a document."""
    count = vectorstore.delete_by_document(document_id)
    elasticsearch_store.delete_by_document(document_id)
    return DeleteVectorsResponse(deleted_count=count)


async def _save_upload(file: UploadFile, file_type: str) -> str:
    suffix = f".{file_type.lower()}"
    with tempfile.NamedTemporaryFile(delete=False, suffix=suffix) as tmp:
        while content := await file.read(1024 * 1024):
            tmp.write(content)
        return tmp.name


def _process_file(
    document_id: int,
    file_type: str,
    doc_version: str,
    file_path: str,
    progress=None,
) -> ProcessResponse:
    if progress:
        progress(stage="PARSING", message="Parsing document")
    parsed = parser.parse_file(file_path, file_type)
    if parsed.is_empty:
        return ProcessResponse(
            document_id=document_id,
            doc_version=doc_version,
            chunk_count=0,
            chunk_ids=[],
            vector_ids=[],
            chunks=[],
            parse_quality=parsed.quality.as_dict(),
            status="READY",
        )

    if progress:
        progress(stage="CHUNKING", message=f"Chunking {len(parsed.blocks)} parsed blocks")
    chunks = chunker.chunk_document(parsed)
    texts = [c["content"] for c in chunks]

    if progress:
        progress(
            stage="EMBEDDING",
            message=f"Embedding {len(chunks)} chunks",
            chunk_count=len(chunks),
        )
    if settings.enable_embedding:
        vectors = embedder.embed_documents(texts)
    else:
        vectors = [[1.0] + [0.0] * (settings.qdrant_vector_size - 1) for _ in texts]

    if progress:
        progress(stage="INDEXING", message=f"Indexing {len(chunks)} chunks")
    vector_ids = vectorstore.upsert_chunks(
        document_id=document_id,
        chunks=chunks,
        vectors=vectors,
        doc_version=doc_version,
    )
    elasticsearch_store.index_chunks(
        document_id=document_id,
        doc_version=doc_version,
        chunks=chunks,
    )
    chunk_results = [
        ChunkResult(
            chunk_id=chunk["chunk_id"],
            chunk_index=chunk["chunk_index"],
            content=chunk["content"],
            token_count=chunk["token_count"],
            page_no=chunk.get("page_no"),
            section_title=chunk.get("section_title"),
            title_path=chunk.get("title_path") or [],
            content_hash=chunk.get("content_hash"),
            char_start=chunk.get("char_start"),
            char_end=chunk.get("char_end"),
            vector_id=vector_id,
            block_type=chunk.get("block_type"),
            chunk_strategy=chunk.get("chunk_strategy"),
            source_location=chunk.get("source_location"),
        )
        for chunk, vector_id in zip(chunks, vector_ids)
    ]

    return ProcessResponse(
        document_id=document_id,
        doc_version=doc_version,
        chunk_count=len(chunks),
        chunk_ids=[chunk["chunk_id"] for chunk in chunks],
        vector_ids=vector_ids,
        chunks=chunk_results,
        parse_quality=parsed.quality.as_dict(),
        status="READY",
    )


def _run_process_task(
    task_id: str,
    document_id: int,
    file_type: str,
    doc_version: str,
    file_path: str,
) -> None:
    _update_task(
        task_id,
        status="PROCESSING",
        stage="STARTED",
        message="Document processing started",
        started_at=_now(),
    )
    try:
        response = _process_file(
            document_id=document_id,
            file_type=file_type,
            doc_version=doc_version,
            file_path=file_path,
            progress=lambda **kwargs: _update_task(
                task_id,
                status="PROCESSING",
                **kwargs,
            ),
        )
        _update_task(
            task_id,
            status=response.status,
            stage="DONE",
            message="Document processing completed",
            chunk_count=response.chunk_count,
            vector_count=len(response.vector_ids),
            chunk_ids=response.chunk_ids,
            vector_ids=response.vector_ids,
            parse_quality=response.parse_quality,
            finished_at=_now(),
        )
    except Exception as exc:
        _update_task(
            task_id,
            status="FAILED",
            stage="FAILED",
            message="Document processing failed",
            error=str(exc),
            finished_at=_now(),
        )
    finally:
        if os.path.exists(file_path):
            os.unlink(file_path)


def _create_task(document_id: int, doc_version: str) -> str:
    task_id = str(uuid4())
    now = _now()
    task = {
        "task_id": task_id,
        "document_id": document_id,
        "doc_version": doc_version,
        "status": "PENDING",
        "stage": "QUEUED",
        "message": "Document processing queued",
        "chunk_count": 0,
        "vector_count": 0,
        "chunk_ids": [],
        "vector_ids": [],
        "parse_quality": None,
        "error": None,
        "created_at": now,
        "updated_at": now,
        "started_at": None,
        "finished_at": None,
    }
    with _task_lock:
        _tasks[task_id] = task
    return task_id


def _update_task(task_id: str, **updates) -> None:
    with _task_lock:
        task = _tasks.get(task_id)
        if not task:
            return
        task.update(updates)
        task["updated_at"] = _now()


def _get_task_response(task_id: str) -> ProcessTaskResponse:
    with _task_lock:
        task = _tasks.get(task_id)
        if not task:
            raise HTTPException(status_code=404, detail="Task not found")
        return ProcessTaskResponse(**task)


def _now() -> str:
    return datetime.now(timezone.utc).isoformat()
