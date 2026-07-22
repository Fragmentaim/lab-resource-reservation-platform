import secrets

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse
from fastapi.middleware.cors import CORSMiddleware
from contextlib import asynccontextmanager

from app.config import settings
from app.core.vectorstore import ensure_collection
from app.api import documents, qa, health, tool_calling


@asynccontextmanager
async def lifespan(app: FastAPI):
    # Startup: ensure Qdrant collection exists
    try:
        ensure_collection()
        print(f"[Startup] Qdrant collection '{settings.qdrant_collection}' ready")
    except Exception as e:
        print(f"[Startup] Warning: Could not connect to Qdrant: {e}")
    yield


app = FastAPI(
    title=settings.app_name,
    version="1.0.0",
    lifespan=lifespan,
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


@app.middleware("http")
async def require_service_token(request: Request, call_next):
    """Only the Java backend may access internal AI operations."""
    if request.url.path in {"/", "/api/v1/ai/health", "/docs", "/openapi.json"}:
        return await call_next(request)
    supplied = request.headers.get("X-AI-Service-Token", "")
    if not supplied or not secrets.compare_digest(supplied, settings.ai_service_token):
        return JSONResponse(status_code=401, content={"detail": "AI service token is required"})
    return await call_next(request)

app.include_router(documents.router, prefix="/api/v1/ai")
app.include_router(qa.router, prefix="/api/v1/ai")
app.include_router(health.router, prefix="/api/v1/ai")
app.include_router(tool_calling.router, prefix="/api/v1/ai")


@app.get("/")
async def root():
    return {"service": settings.app_name, "status": "running"}
