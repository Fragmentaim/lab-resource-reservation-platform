from pydantic import BaseModel, Field
from typing import List, Optional
from pydantic import BaseModel, Field


# ---- Document Processing ----

class ProcessRequest(BaseModel):
    document_id: int
    file_type: str  # PDF/DOCX/MD/TXT


class ChunkResult(BaseModel):
    chunk_id: str
    chunk_index: int
    content: str
    token_count: int
    page_no: Optional[int] = None
    section_title: Optional[str] = None
    title_path: List[str] = Field(default_factory=list)
    content_hash: Optional[str] = None
    char_start: Optional[int] = None
    char_end: Optional[int] = None
    vector_id: str
    block_type: Optional[str] = None
    chunk_strategy: Optional[str] = None
    source_location: Optional[dict] = None


class ParseQualityReport(BaseModel):
    provider: str
    provider_version: str
    parse_mode: str
    unit_count: int
    non_empty_unit_count: int
    character_count: int
    heading_count: int = 0
    table_count: int = 0
    image_count: int = 0
    scanned_unit_count: int = 0
    ocr_unit_count: int = 0
    ocr_character_count: int = 0
    ocr_average_confidence: Optional[float] = None
    empty_unit_count: int = 0
    quality_score: float
    warnings: List[str] = Field(default_factory=list)
    decisions: List[dict] = Field(default_factory=list)


class ProcessResponse(BaseModel):
    document_id: int
    doc_version: str = "v1"
    chunk_count: int
    chunk_ids: List[str]
    vector_ids: List[str]
    chunks: List[ChunkResult] = Field(default_factory=list)
    parse_quality: Optional[ParseQualityReport] = None
    status: str


class ProcessTaskResponse(BaseModel):
    task_id: str
    document_id: int
    doc_version: str = "v1"
    status: str
    stage: Optional[str] = None
    message: Optional[str] = None
    chunk_count: int = 0
    vector_count: int = 0
    chunk_ids: List[str] = Field(default_factory=list)
    vector_ids: List[str] = Field(default_factory=list)
    parse_quality: Optional[ParseQualityReport] = None
    error: Optional[str] = None
    created_at: str
    updated_at: str
    started_at: Optional[str] = None
    finished_at: Optional[str] = None


class DeleteVectorsResponse(BaseModel):
    deleted_count: int


# ---- QA ----

class ChatMessage(BaseModel):
    role: str  # "user" or "assistant"
    content: str


class KnowledgeSearchRequest(BaseModel):
    question: str
    document_ids: List[int] = Field(default_factory=list)
    top_k: Optional[int] = None
    score_threshold: Optional[float] = None


class KnowledgeCandidate(BaseModel):
    chunk_uid: str
    document_id: int
    doc_version: Optional[str] = None
    chunk_index: Optional[int] = None
    page_no: Optional[int] = None
    section_title: Optional[str] = None
    title_path: List[str] = Field(default_factory=list)
    content_hash: Optional[str] = None
    token_count: Optional[int] = None
    score: float
    retrieval_score: Optional[float] = None
    rerank_score: Optional[float] = None
    rerank_provider: Optional[str] = None
    retrieval_source: Optional[str] = None
    locator: str


class KnowledgeSearchResponse(BaseModel):
    query: str
    candidates: List[KnowledgeCandidate] = Field(default_factory=list)


class KnowledgeOpenChunksRequest(BaseModel):
    chunk_uids: List[str] = Field(default_factory=list)
    document_ids: List[int] = Field(default_factory=list)


class KnowledgeChunk(BaseModel):
    chunk_uid: str
    document_id: int
    doc_version: Optional[str] = None
    chunk_index: Optional[int] = None
    page_no: Optional[int] = None
    section_title: Optional[str] = None
    title_path: List[str] = Field(default_factory=list)
    content_hash: Optional[str] = None
    token_count: Optional[int] = None
    content: str


class KnowledgeOpenChunksResponse(BaseModel):
    chunks: List[KnowledgeChunk] = Field(default_factory=list)


class SessionSummaryRequest(BaseModel):
    existing_summary: Optional[str] = None
    new_turns: List[ChatMessage] = Field(default_factory=list)
    max_summary_tokens: Optional[int] = None


class SessionSummaryResponse(BaseModel):
    summary: str
    summary_tokens: int


# ---- Health ----

class HealthResponse(BaseModel):
    status: str
    qdrant: str
    llm: str
    chat_model: str
    embedding_model: str
    retrieval_mode: str
    hybrid_search: str
    rerank: str
