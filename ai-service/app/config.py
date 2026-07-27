from pathlib import Path
from typing import Optional
import os
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=str(Path(__file__).resolve().parents[1] / ".env"),
        # Old deployments may still contain removed OCR/parser variables.
        # Ignore them during the one-time transition to the Docling pipeline.
        extra="ignore",
    )

    app_name: str = "Lab Knowledge AI Service"
    debug: bool = True

    # OpenAI-compatible API
    llm_base_url: str = "http://127.0.0.1:11434/v1"
    llm_api_key: str = ""
    llm_api_mode: str = "chat_completions"
    chat_model: str = "qwen2.5:7b"
    llm_timeout_seconds: float = 60.0
    llm_max_retries: int = 1
    # Provider-neutral API clients do not expose reasoning controls directly.
    # These values are forwarded through the OpenAI-compatible ``extra_body``
    # only when explicitly configured. MiniMax-M3 accepts ``adaptive`` for
    # thinking; some gateways additionally understand effort values such as
    # ``max``.
    llm_thinking_mode: str = ""
    llm_reasoning_effort: str = ""
    # Evaluation-only local capture. Never enable this in a shared deployment:
    # it may contain user prompts and tool-result evidence.
    llm_debug_capture_path: str = ""
    llm_fallback_base_url: str = ""
    llm_fallback_api_key: str = ""
    llm_fallback_chat_model: str = ""
    llm_circuit_failure_threshold: int = 3
    llm_circuit_reset_seconds: int = 30
    ai_service_token: str = "change-me-before-production"
    mcp_backend_base_url: str = "http://127.0.0.1:8082"
    mcp_access_token: str = ""
    mcp_request_timeout_seconds: float = 30.0
    embedding_model: str = "text-embedding-3-large"
    embedding_base_url: str = ""
    embedding_api_key: str = ""
    embedding_batch_size: int = 32
    embedding_dimensions: Optional[int] = None
    enable_embedding: bool = False
    retrieval_mode: str = "keyword"
    enable_hybrid_search: bool = False
    hybrid_rrf_k: int = 60
    hybrid_vector_weight: float = 1.0
    hybrid_keyword_weight: float = 1.0
    elasticsearch_url: str = "http://localhost:9200"
    elasticsearch_index: str = "lab_knowledge_chunks"
    elasticsearch_username: str = ""
    elasticsearch_password: str = ""
    elasticsearch_verify_certs: bool = False
    elasticsearch_request_timeout_seconds: float = 10.0
    # Built-in "cjk" works without plugins. After installing analysis-ik,
    # these may be changed to ik_max_word / ik_smart without code changes.
    elasticsearch_index_analyzer: str = "cjk"
    elasticsearch_search_analyzer: str = "cjk"

    # Rerank configuration. An API is preferred when configured; the local
    # CrossEncoder is opt-in so a normal service process does not load a GPU model.
    enable_rerank: bool = False
    rerank_base_url: str = ""
    rerank_api_key: str = ""
    rerank_model: str = "BAAI/bge-reranker-v2-m3"
    rerank_timeout_seconds: float = 15.0
    rerank_candidate_multiplier: int = 4
    rerank_max_candidates: int = 20
    rerank_document_max_chars: int = 2000
    use_local_reranker: bool = False
    local_reranker_model: str = "Qwen/Qwen3-Reranker-0.6B"
    local_reranker_device: str = "cuda"
    local_reranker_batch_size: int = 8

    # Safe fallback when the Java caller does not provide a model capability profile.
    context_window_tokens: int = 12000
    max_output_tokens: int = 2000
    safety_margin_tokens: int = 512

    # 本地 Embedding 模型配置
    use_local_embedding: bool = True
    local_embedding_model: str = "Qwen/Qwen3-Embedding-4B"
    hf_hub_cache: str = ""

    qdrant_url: str = "http://localhost:6333"
    qdrant_collection: str = "lab_knowledge"
    qdrant_vector_size: int = 2560  # Qwen3-Embedding-4B 的向量维度
    # Used only when no remote Qdrant service is reachable. Keep benchmark or
    # development indexes on an explicitly configured data disk instead of a
    # system temporary directory.
    qdrant_local_path: str = ""

    # Docling HybridChunker uses this as a tokenizer-aware maximum rather than
    # a character count. It preserves document structure and splits only when
    # a structured unit exceeds the model budget.
    chunk_size: int = 512
    top_k: int = 5
    score_threshold: float = 0.5

    # Unified document ingestion. Docling owns format parsing, OCR, layout,
    # tables, formulas, pictures and structure-aware chunking.
    docling_device: str = "auto"
    docling_num_threads: int = 8
    docling_model_cache_dir: str = "./.model-cache/docling"
    docling_do_ocr: bool = True
    docling_do_table_structure: bool = True
    docling_do_formula_enrichment: bool = True
    docling_do_picture_classification: bool = True
    docling_images_scale: float = 3.0
    # off | local | api. API mode expects an OpenAI-compatible multimodal
    # chat-completions endpoint and is disabled unless explicitly configured.
    docling_picture_description_mode: str = "off"
    docling_picture_local_model: str = "ibm-granite/granite-vision-3.3-2b"
    docling_picture_api_url: str = ""
    docling_picture_api_key: str = ""
    docling_picture_model: str = ""
    docling_picture_timeout_seconds: float = 300.0
    docling_picture_max_tokens: int = 1200
    document_download_timeout_seconds: float = 120.0
    document_download_max_bytes: int = 268435456

settings = Settings()
os.environ.setdefault("HF_HOME", settings.docling_model_cache_dir)
os.environ.setdefault("TORCH_HOME", str(Path(settings.docling_model_cache_dir) / "torch"))
os.environ.setdefault("HF_HUB_DISABLE_SYMLINKS_WARNING", "1")
