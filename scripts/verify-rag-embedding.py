"""Run a minimal GPU smoke test for the local Qwen embedding model."""

from __future__ import annotations

import argparse
import json
import time
from pathlib import Path


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--model-path",
        default="Qwen/Qwen3-Embedding-0.6B",
        help="Local Hugging Face model directory.",
    )
    parser.add_argument("--expected-dimension", type=int, default=1024)
    args = parser.parse_args()

    import torch
    from sentence_transformers import SentenceTransformer

    if not torch.cuda.is_available():
        raise RuntimeError("CUDA is unavailable. Run this with the verified GPU Python runtime.")

    model_path = Path(args.model_path)
    model_file = model_path / "model.safetensors"
    if not model_file.is_file():
        raise FileNotFoundError(f"Missing model weights: {model_file}")

    samples = [
        "实验室预约需要经过审批吗？",
        "如何取消已经提交的实验室预约？",
        "今天天气怎么样？",
    ]
    started_at = time.perf_counter()
    model = SentenceTransformer(str(model_path), device="cuda", trust_remote_code=True)
    vectors = model.encode(samples, normalize_embeddings=True, show_progress_bar=False)
    elapsed_ms = round((time.perf_counter() - started_at) * 1000, 2)

    if vectors.shape != (len(samples), args.expected_dimension):
        raise RuntimeError(
            f"Unexpected vector shape {vectors.shape}; expected "
            f"({len(samples)}, {args.expected_dimension})"
        )

    result = {
        "device": torch.cuda.get_device_name(0),
        "cuda_available": True,
        "model_path": str(model_path),
        "vector_shape": list(vectors.shape),
        "load_and_encode_ms": elapsed_ms,
        "approval_vs_cancel_similarity": round(float(vectors[0] @ vectors[1]), 6),
        "approval_vs_unrelated_similarity": round(float(vectors[0] @ vectors[2]), 6),
    }
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
