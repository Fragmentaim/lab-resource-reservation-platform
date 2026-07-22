"""Provider routing, circuit breaking and capability checks for model calls."""

from dataclasses import dataclass
from threading import Lock
from time import monotonic
from typing import Callable, Optional, TypeVar

from openai import OpenAI

try:
    from anthropic import Anthropic
except ImportError:  # pragma: no cover - the dependency is installed in runtime images
    Anthropic = None  # type: ignore[assignment,misc]

from app.config import settings


@dataclass(frozen=True)
class ModelCapability:
    model: str
    api_mode: str
    supports_native_tool_calling: bool
    timeout_seconds: float
    max_retries: int


@dataclass(frozen=True)
class ProviderRoute:
    name: str
    base_url: str
    api_key: str
    chat_model: str
    tool_model: str


@dataclass
class ProviderState:
    consecutive_failures: int = 0
    open_until: float = 0.0
    half_open_in_flight: bool = False


class ModelGatewayError(RuntimeError):
    pass


_clients: dict[tuple, object] = {}
_states: dict[str, ProviderState] = {}
_lock = Lock()
T = TypeVar("T")


def chat_capability(requested_model: Optional[str] = None) -> ModelCapability:
    return _capability(requested_model or settings.chat_model)


def tool_capability(requested_model: Optional[str] = None) -> ModelCapability:
    return _capability(requested_model or settings.tool_calling_model or settings.chat_model)


def get_client() -> object:
    """Compatibility entrypoint for callers that only need the primary client."""
    return _client_for(_routes()[0])


def require_native_tool_calling(requested_model: Optional[str] = None) -> ModelCapability:
    capability = tool_capability(requested_model)
    if not capability.supports_native_tool_calling:
        raise ValueError("Native tool calling requires llm_api_mode=chat_completions or anthropic")
    return capability


def _capability(model: str) -> ModelCapability:
    return ModelCapability(
        model=model,
        api_mode=settings.llm_api_mode,
        supports_native_tool_calling=settings.llm_api_mode in {"chat_completions", "anthropic"},
        timeout_seconds=max(1.0, settings.llm_timeout_seconds),
        max_retries=max(0, settings.llm_max_retries),
    )


def _openai_base_url() -> str:
    return _normalize_base_url(settings.llm_base_url)


def _normalize_base_url(value: str) -> str:
    base_url = value.rstrip("/")
    return base_url if base_url.endswith("/v1") else f"{base_url}/v1"


def invoke(kind: str, requested_model: Optional[str], operation: Callable[[OpenAI, str], T]) -> tuple[T, dict]:
    """Call the first healthy route and return privacy-safe routing metadata."""
    errors: list[str] = []
    routes = _routes()
    for index, route in enumerate(routes):
        if not _try_acquire(route.name):
            errors.append(f"{route.name}:circuit_open")
            continue
        model = requested_model or (route.tool_model if kind == "tool" else route.chat_model)
        try:
            result = operation(_client_for(route), model)
            _mark_success(route.name)
            return result, {
                "provider": route.name,
                "model": model,
                "attempt": index + 1,
                "fallback_used": index > 0,
                "requested_thinking_mode": settings.llm_thinking_mode or None,
                "requested_reasoning_effort": settings.llm_reasoning_effort or None,
            }
        except Exception as exc:  # provider SDK errors are intentionally normalized at this boundary
            _mark_failure(route.name)
            errors.append(f"{route.name}:{type(exc).__name__}")
    raise ModelGatewayError("No healthy model route: " + ", ".join(errors))


def chat_completion_options() -> dict:
    """Return explicit provider reasoning controls without hard-coding a vendor.

    OpenAI's Python SDK permits OpenAI-compatible extensions through
    ``extra_body``. Keeping the options absent by default protects local
    Ollama/Qwen deployments that reject unknown request fields.
    """
    if settings.llm_api_mode == "anthropic":
        return {}
    extra_body: dict[str, object] = {}
    if settings.llm_thinking_mode.strip():
        extra_body["thinking"] = {"type": settings.llm_thinking_mode.strip()}
    if settings.llm_reasoning_effort.strip():
        extra_body["reasoning_effort"] = settings.llm_reasoning_effort.strip()
    return {"extra_body": extra_body} if extra_body else {}


def anthropic_message_options() -> dict:
    """Return MiniMax Anthropic-compatible thinking controls.

    MiniMax's Anthropic interface calls its highest available reasoning mode
    ``adaptive``. We map the project-level ``max`` effort setting to that
    provider-native control and retain the requested effort in route metadata.
    """
    if settings.llm_api_mode != "anthropic":
        return {}
    thinking_mode = settings.llm_thinking_mode.strip().lower()
    if not thinking_mode and settings.llm_reasoning_effort.strip().lower() == "max":
        thinking_mode = "adaptive"
    if not thinking_mode:
        return {}
    if thinking_mode in {"max", "high", "on", "enabled"}:
        thinking_mode = "adaptive"
    return {"thinking": {"type": thinking_mode}}


def health_snapshot() -> list[dict]:
    now = monotonic()
    with _lock:
        return [{
            "provider": route.name,
            "state": "OPEN" if state.open_until > now else ("HALF_OPEN" if state.half_open_in_flight else "CLOSED"),
            "consecutive_failures": state.consecutive_failures,
            "retry_after_seconds": max(0, round(state.open_until - now)),
        } for route in _routes() for state in [_states.setdefault(route.name, ProviderState())]]


def _routes() -> list[ProviderRoute]:
    primary = ProviderRoute("primary", settings.llm_base_url, settings.llm_api_key,
                            settings.chat_model, settings.tool_calling_model or settings.chat_model)
    routes = [primary]
    if settings.llm_fallback_base_url.strip() and settings.llm_fallback_chat_model.strip():
        routes.append(ProviderRoute("fallback", settings.llm_fallback_base_url, settings.llm_fallback_api_key,
                                    settings.llm_fallback_chat_model,
                                    settings.llm_fallback_tool_calling_model or settings.llm_fallback_chat_model))
    return routes


def _client_for(route: ProviderRoute) -> object:
    normalized_base_url = (_normalize_anthropic_base_url(route.base_url)
                           if settings.llm_api_mode == "anthropic"
                           else _normalize_base_url(route.base_url))
    signature = (settings.llm_api_mode, normalized_base_url, route.api_key, settings.llm_timeout_seconds,
                 max(0, settings.llm_max_retries))
    with _lock:
        client = _clients.get(signature)
        if client is None:
            if settings.llm_api_mode == "anthropic":
                if Anthropic is None:
                    raise RuntimeError("Anthropic SDK is required when llm_api_mode=anthropic")
                client = Anthropic(api_key=route.api_key, base_url=normalized_base_url,
                                   timeout=max(1.0, settings.llm_timeout_seconds),
                                   max_retries=max(0, settings.llm_max_retries))
            else:
                client = OpenAI(api_key=route.api_key, base_url=normalized_base_url,
                                timeout=max(1.0, settings.llm_timeout_seconds),
                                max_retries=max(0, settings.llm_max_retries))
            _clients[signature] = client
        return client


def _normalize_anthropic_base_url(value: str) -> str:
    base_url = value.rstrip("/")
    return base_url[:-3] if base_url.endswith("/v1") else base_url


def _try_acquire(provider: str) -> bool:
    now = monotonic()
    with _lock:
        state = _states.setdefault(provider, ProviderState())
        if state.open_until > now:
            return False
        if state.open_until:
            if state.half_open_in_flight:
                return False
            state.half_open_in_flight = True
        return True


def _mark_success(provider: str) -> None:
    with _lock:
        _states[provider] = ProviderState()


def _mark_failure(provider: str) -> None:
    with _lock:
        state = _states.setdefault(provider, ProviderState())
        state.half_open_in_flight = False
        state.consecutive_failures += 1
        if state.consecutive_failures >= max(1, settings.llm_circuit_failure_threshold):
            state.open_until = monotonic() + max(1, settings.llm_circuit_reset_seconds)
