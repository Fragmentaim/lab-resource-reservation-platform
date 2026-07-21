"""One provider boundary for model selection, capabilities and client policy."""

from dataclasses import dataclass
from typing import Optional

from openai import OpenAI

from app.config import settings


@dataclass(frozen=True)
class ModelCapability:
    model: str
    api_mode: str
    supports_native_tool_calling: bool
    timeout_seconds: float
    max_retries: int


_client: Optional[OpenAI] = None
_client_signature: Optional[tuple] = None


def chat_capability(requested_model: Optional[str] = None) -> ModelCapability:
    return _capability(requested_model or settings.chat_model)


def tool_capability(requested_model: Optional[str] = None) -> ModelCapability:
    return _capability(requested_model or settings.tool_calling_model or settings.chat_model)


def get_client() -> OpenAI:
    """Return a cached OpenAI-compatible client, refreshed after config changes."""
    global _client, _client_signature
    signature = (
        _openai_base_url(), settings.llm_api_key, settings.llm_timeout_seconds,
        max(0, settings.llm_max_retries),
    )
    if _client is None or _client_signature != signature:
        _client = OpenAI(
            api_key=settings.llm_api_key,
            base_url=signature[0],
            timeout=max(1.0, settings.llm_timeout_seconds),
            max_retries=max(0, settings.llm_max_retries),
        )
        _client_signature = signature
    return _client


def require_native_tool_calling(requested_model: Optional[str] = None) -> ModelCapability:
    capability = tool_capability(requested_model)
    if not capability.supports_native_tool_calling:
        raise ValueError("Native tool calling requires llm_api_mode=chat_completions")
    return capability


def _capability(model: str) -> ModelCapability:
    return ModelCapability(
        model=model,
        api_mode=settings.llm_api_mode,
        supports_native_tool_calling=settings.llm_api_mode == "chat_completions",
        timeout_seconds=max(1.0, settings.llm_timeout_seconds),
        max_retries=max(0, settings.llm_max_retries),
    )


def _openai_base_url() -> str:
    base_url = settings.llm_base_url.rstrip("/")
    return base_url if base_url.endswith("/v1") else f"{base_url}/v1"
