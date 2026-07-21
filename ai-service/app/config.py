from pathlib import Path
from typing import Optional
import os
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=str(Path(__file__).resolve().parents[1] / ".env"),
    )

    app_name: str = "Lab Knowledge AI Service"
    debug: bool = True

    # OpenAI-compatible API
    llm_base_url: str = "http://127.0.0.1:11434/v1"
    llm_api_key: str = ""
    llm_api_mode: str = "chat_completions"
    chat_model: str = "qwen2.5:7b"
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

    # Rerank configuration. Prefer cloud rerank APIs to avoid local model loading.
    enable_rerank: bool = False
    rerank_base_url: str = ""
    rerank_api_key: str = ""
    rerank_model: str = "BAAI/bge-reranker-v2-m3"
    rerank_timeout_seconds: float = 15.0
    rerank_candidate_multiplier: int = 4
    rerank_max_candidates: int = 20
    rerank_document_max_chars: int = 2000

    # Safe fallback when the Java caller does not provide a model capability profile.
    context_window_tokens: int = 12000
    max_output_tokens: int = 2000
    safety_margin_tokens: int = 512
    summary_max_tokens: int = 1000
    enable_query_rewrite: bool = True

    # 本地 Embedding 模型配置
    use_local_embedding: bool = True
    local_embedding_model: str = "Qwen/Qwen3-Embedding-4B"
    hf_hub_cache: str = ""

    qdrant_url: str = "http://localhost:6333"
    qdrant_collection: str = "lab_knowledge"
    qdrant_vector_size: int = 2560  # Qwen3-Embedding-4B 的向量维度

    chunk_size: int = 512
    chunk_overlap: int = 64
    top_k: int = 5
    score_threshold: float = 0.5

    # Local document OCR. The engine is lazily loaded because the normal
    # native-text PDF path must stay lightweight.
    ocr_enabled: bool = True
    ocr_provider: str = "paddle"
    ocr_device: str = "gpu:0"
    ocr_language: str = "ch"
    ocr_model_cache_dir: str = "./.model-cache/paddlex"
    ocr_min_native_chars: int = 80
    ocr_hybrid_image_coverage: float = 0.20
    ocr_scan_image_coverage: float = 0.35

settings = Settings()
os.environ.setdefault("PADDLE_PDX_CACHE_HOME", settings.ocr_model_cache_dir)
os.environ.setdefault("PADDLE_HOME", "./.model-cache/paddle")
os.environ.setdefault("DISABLE_MODEL_SOURCE_CHECK", "True")
