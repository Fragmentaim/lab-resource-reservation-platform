from fastapi import FastAPI
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

app.include_router(documents.router, prefix="/api/v1/ai")
app.include_router(qa.router, prefix="/api/v1/ai")
app.include_router(health.router, prefix="/api/v1/ai")
app.include_router(tool_calling.router, prefix="/api/v1/ai")


@app.get("/")
async def root():
    return {"service": settings.app_name, "status": "running"}
