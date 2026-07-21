"""Local OpenAI-compatible rerank endpoint backed by Qwen3-Reranker on CUDA.

The endpoint deliberately implements only the small `/v1/rerank` contract used by
the lab knowledge assistant.  It keeps raw documents out of logs and loads the
model lazily so the service can be started before the GPU is needed.
"""

from __future__ import annotations

import asyncio
import os
from contextlib import asynccontextmanager
from dataclasses import dataclass
from typing import Annotated

import torch
from fastapi import FastAPI, Header, HTTPException
from pydantic import BaseModel, Field, field_validator
from transformers import AutoModelForCausalLM, AutoTokenizer


MODEL_PATH = os.getenv("LOCAL_RERANKER_MODEL_PATH", "Qwen/Qwen3-Reranker-0.6B")
API_KEY = os.getenv("LOCAL_RERANKER_API_KEY", "")
MAX_DOCUMENTS = int(os.getenv("LOCAL_RERANKER_MAX_DOCUMENTS", "32"))
MAX_QUERY_CHARS = int(os.getenv("LOCAL_RERANKER_MAX_QUERY_CHARS", "2000"))
MAX_DOCUMENT_CHARS = int(os.getenv("LOCAL_RERANKER_MAX_DOCUMENT_CHARS", "6000"))
MAX_LENGTH = int(os.getenv("LOCAL_RERANKER_MAX_LENGTH", "4096"))
BATCH_SIZE = int(os.getenv("LOCAL_RERANKER_BATCH_SIZE", "2"))


@dataclass
class QwenReranker:
    model: object
    tokenizer: object
    token_false_id: int
    token_true_id: int
    prefix_tokens: list[int]
    suffix_tokens: list[int]


_model: QwenReranker | None = None
_model_lock = asyncio.Lock()
_score_lock = asyncio.Lock()


class RerankRequest(BaseModel):
    model: str = "Qwen3-Reranker-0.6B"
    query: Annotated[str, Field(min_length=1, max_length=MAX_QUERY_CHARS)]
    documents: Annotated[list[str], Field(min_length=1, max_length=MAX_DOCUMENTS)]
    top_n: int | None = Field(default=None, ge=1, le=MAX_DOCUMENTS)
    return_documents: bool = False

    @field_validator("documents")
    @classmethod
    def reject_blank_documents(cls, documents: list[str]) -> list[str]:
        if any(not document.strip() for document in documents):
            raise ValueError("documents must not contain blank text")
        return documents


async def _get_model() -> QwenReranker:
    global _model
    if _model is not None:
        return _model

    async with _model_lock:
        if _model is None:
            if not torch.cuda.is_available():
                raise RuntimeError("CUDA is unavailable; refusing CPU reranking")
            _model = await asyncio.to_thread(_load_qwen_reranker)
            print("[local-reranker] Qwen3 model loaded on cuda:0; " f"max_length={MAX_LENGTH}", flush=True)
    return _model


def _load_qwen_reranker() -> QwenReranker:
    """Load via Qwen's official CausalLM scoring recipe, not a generic classifier.

    The generic CrossEncoder path can create an untrained classification head with
    newer Transformers versions. Qwen3-Reranker stores its relevance decision in
    the next-token logits for `yes` and `no`, so retain that model contract.
    """
    tokenizer = AutoTokenizer.from_pretrained(MODEL_PATH, padding_side="left", trust_remote_code=True)
    model = AutoModelForCausalLM.from_pretrained(
        MODEL_PATH,
        torch_dtype=torch.float16,
        trust_remote_code=True,
    ).cuda().eval()
    prefix = (
        '<|im_start|>system\nJudge whether the Document meets the requirements based on '
        'the Query and the Instruct provided. Note that the answer can only be "yes" or '
        '"no".<|im_end|>\n<|im_start|>user\n'
    )
    suffix = '<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n'
    return QwenReranker(
        model=model,
        tokenizer=tokenizer,
        token_false_id=tokenizer.convert_tokens_to_ids("no"),
        token_true_id=tokenizer.convert_tokens_to_ids("yes"),
        prefix_tokens=tokenizer.encode(prefix, add_special_tokens=False),
        suffix_tokens=tokenizer.encode(suffix, add_special_tokens=False),
    )


def _verify_authorization(authorization: str | None) -> None:
    if not API_KEY:
        return
    if authorization != f"Bearer {API_KEY}":
        raise HTTPException(status_code=401, detail="invalid bearer token")


@asynccontextmanager
async def lifespan(_: FastAPI):
    yield
    if torch.cuda.is_available():
        torch.cuda.empty_cache()


app = FastAPI(title="Local GPU Reranker", version="1.0.0", lifespan=lifespan)


@app.get("/healthz")
async def healthz() -> dict[str, object]:
    return {
        "status": "ok",
        "model_loaded": _model is not None,
        "cuda_available": torch.cuda.is_available(),
        "model_path": MODEL_PATH,
    }


@app.post("/v1/rerank")
async def rerank(request: RerankRequest, authorization: str | None = Header(default=None)) -> dict[str, object]:
    _verify_authorization(authorization)
    model = await _get_model()
    documents = [document[:MAX_DOCUMENT_CHARS] for document in request.documents]
    pairs = [(request.query, document) for document in documents]

    # A 0-1 score is easier to store, compare and display than Qwen's raw logits.
    async with _score_lock:
        scores = await asyncio.to_thread(_score_pairs, model, pairs)

    top_n = min(request.top_n or len(documents), len(documents))
    ranked_indices = sorted(range(len(documents)), key=lambda index: float(scores[index]), reverse=True)[:top_n]
    results = [
        {"index": index, "relevance_score": round(float(scores[index]), 8)}
        for index in ranked_indices
    ]
    return {"object": "list", "model": request.model, "results": results}


def _score_pairs(reranker: QwenReranker, pairs: list[tuple[str, str]]) -> list[float]:
    task = "Given a web search query, retrieve relevant passages that answer the query"
    scores: list[float] = []
    for start in range(0, len(pairs), BATCH_SIZE):
        batch = pairs[start:start + BATCH_SIZE]
        formatted = [
            f"<Instruct>: {task}\n<Query>: {query}\n<Document>: {document}"
            for query, document in batch
        ]
        inputs = reranker.tokenizer(
            formatted,
            padding=False,
            truncation="longest_first",
            return_attention_mask=False,
            max_length=MAX_LENGTH - len(reranker.prefix_tokens) - len(reranker.suffix_tokens),
        )
        for index, token_ids in enumerate(inputs["input_ids"]):
            inputs["input_ids"][index] = reranker.prefix_tokens + token_ids + reranker.suffix_tokens
        tensors = reranker.tokenizer.pad(inputs, padding=True, return_tensors="pt", max_length=MAX_LENGTH)
        tensors = {key: value.to("cuda") for key, value in tensors.items()}
        with torch.inference_mode():
            logits = reranker.model(**tensors).logits[:, -1, :]
            yes_no_logits = torch.stack(
                [logits[:, reranker.token_false_id], logits[:, reranker.token_true_id]],
                dim=1,
            )
            scores.extend(torch.nn.functional.log_softmax(yes_no_logits, dim=1)[:, 1].exp().tolist())
    return scores
