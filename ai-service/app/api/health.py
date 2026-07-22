from fastapi import APIRouter
from app.models.schemas import HealthResponse
from app.config import settings
from app.core import vectorstore, reranker, model_gateway
import httpx

router = APIRouter(tags=["health"])


@router.get("/health", response_model=HealthResponse)
async def health_check():
    """Check health of AI service and dependencies."""
    qdrant_status = "connected"
    try:
        vectorstore.get_client().get_collections()
    except Exception:
        qdrant_status = "disconnected"

    # Some OpenAI-compatible providers support chat completions but do not expose
    # a reliable /v1/models endpoint, so treat that probe as advisory only.
    llm_configured = bool(settings.llm_base_url and settings.llm_api_key and settings.chat_model)
    llm_status = "configured" if llm_configured else "not_configured"
    if llm_configured:
        try:
            headers = {"Authorization": f"Bearer {settings.llm_api_key}"}
            async with httpx.AsyncClient() as client:
                resp = await client.get(
                    f"{_openai_base_url()}/models",
                    headers=headers,
                    timeout=5,
                )
                if resp.status_code < 400:
                    llm_status = "connected"
                elif resp.status_code in (401, 403):
                    llm_status = f"auth_error_{resp.status_code}"
                else:
                    llm_status = f"models_unverified_{resp.status_code}"
        except Exception:
            llm_status = "models_unverified"

    return HealthResponse(
        status="ok" if qdrant_status == "connected" and llm_configured and not llm_status.startswith("auth_error") else "degraded",
        qdrant=qdrant_status,
        llm=llm_status,
        chat_model=settings.chat_model,
        embedding_model=settings.local_embedding_model if settings.use_local_embedding else settings.embedding_model,
        retrieval_mode=settings.retrieval_mode,
        hybrid_search="enabled" if settings.enable_hybrid_search else "disabled",
        rerank=reranker.status(),
    )


def _openai_base_url() -> str:
    base_url = settings.llm_base_url.rstrip("/")
    if base_url.endswith("/v1"):
        return base_url
    return f"{base_url}/v1"


@router.get("/models")
async def model_health():
    """Internal route health; protected by the service-token middleware."""
    return {"routes": model_gateway.health_snapshot()}
