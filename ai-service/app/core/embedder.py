from app.config import settings
from typing import List
from openai import OpenAI
import os
import site
import sys
from pathlib import Path

# 本地模型缓存
_local_model = None


def get_client() -> OpenAI:
    """获取 OpenAI 客户端（用于 API 模式）"""
    from openai import OpenAI
    return OpenAI(
        api_key=settings.llm_api_key,
        base_url=_openai_base_url(),
    )


def get_embedding_client() -> OpenAI:
    """Get an OpenAI-compatible client dedicated to embedding calls."""
    from openai import OpenAI

    return OpenAI(
        api_key=settings.embedding_api_key or settings.llm_api_key,
        base_url=_embedding_base_url(),
    )


def _openai_base_url() -> str:
    base_url = settings.llm_base_url.rstrip("/")
    if base_url.endswith("/v1"):
        return base_url
    return f"{base_url}/v1"


def _embedding_base_url() -> str:
    base_url = (settings.embedding_base_url or settings.llm_base_url).rstrip("/")
    if base_url.endswith("/v1"):
        return base_url
    return f"{base_url}/v1"


def _get_local_model():
    """获取本地 Embedding 模型"""
    global _local_model
    if _local_model is None:
        if settings.hf_hub_cache:
            cache_root = Path(settings.hf_hub_cache).resolve()
            cache_root.mkdir(parents=True, exist_ok=True)
            os.environ["HF_HOME"] = str(cache_root.parent)
            os.environ["HF_HUB_CACHE"] = str(cache_root)
            os.environ["HF_XET_CACHE"] = str(cache_root.parent / "xet")

        user_site = site.getusersitepackages()
        sys.path = [p for p in sys.path if os.path.normcase(p) != os.path.normcase(user_site)]

        from sentence_transformers import SentenceTransformer
        import torch
        import time

        print(f"正在加载本地 Embedding 模型: {settings.local_embedding_model}")
        start = time.time()

        device = 'cuda' if torch.cuda.is_available() else 'cpu'
        _local_model = SentenceTransformer(
            settings.local_embedding_model,
            device=device,
            cache_folder=settings.hf_hub_cache or None,
        )

        print(f"模型加载完成！耗时: {time.time() - start:.1f} 秒，设备: {device}")

    return _local_model


def embed_query(text: str) -> List[float]:
    """将查询文本转换为向量"""
    if settings.use_local_embedding:
        # 使用本地模型
        model = _get_local_model()
        embedding = model.encode(text)
        return embedding.tolist()
    else:
        # 使用 API
        if not settings.enable_embedding:
            raise RuntimeError("Embedding is disabled")

        client = get_embedding_client()
        kwargs = _embedding_kwargs(text)
        res = client.embeddings.create(**kwargs)
        return res.data[0].embedding


def embed_documents(texts: List[str]) -> List[List[float]]:
    """将文档列表转换为向量"""
    if settings.use_local_embedding:
        # 使用本地模型
        model = _get_local_model()
        embeddings = model.encode(texts)
        return embeddings.tolist()
    else:
        # 使用 API
        if not settings.enable_embedding:
            raise RuntimeError("Embedding is disabled")

        client = get_embedding_client()
        vectors = []
        batch_size = max(1, settings.embedding_batch_size)
        for i in range(0, len(texts), batch_size):
            batch = texts[i:i + batch_size]
            kwargs = _embedding_kwargs(batch)
            res = client.embeddings.create(**kwargs)
            vectors.extend(item.embedding for item in res.data)
        return vectors


def _embedding_kwargs(input_text):
    kwargs = {
        "model": settings.embedding_model,
        "input": input_text,
    }
    if settings.embedding_dimensions:
        kwargs["dimensions"] = settings.embedding_dimensions
    return kwargs
