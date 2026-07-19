"""Run a minimal GPU smoke test for the local Qwen reranker model."""

from __future__ import annotations

import argparse
import json
import time
from pathlib import Path


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--model-path",
        default=r"D:\AI-Models\models\Qwen3-Reranker-0.6B",
        help="Local Hugging Face model directory.",
    )
    args = parser.parse_args()

    import torch
    from sentence_transformers import CrossEncoder

    if not torch.cuda.is_available():
        raise RuntimeError("CUDA is unavailable. Run this with the verified GPU Python runtime.")

    model_path = Path(args.model_path)
    model_file = model_path / "model.safetensors"
    if not model_file.is_file():
        raise FileNotFoundError(f"Missing model weights: {model_file}")

    query = "实验室预约提交后如何取消？"
    candidates = [
        "用户可在我的预约页面取消仍处于已预约状态的记录，并填写可选的取消原因。",
        "设备使用前应检查防护装置和电源状态。",
    ]
    started_at = time.perf_counter()
    model = CrossEncoder(str(model_path), device="cuda", trust_remote_code=True)
    scores = model.predict([(query, candidate) for candidate in candidates], show_progress_bar=False)
    elapsed_ms = round((time.perf_counter() - started_at) * 1000, 2)

    if scores[0] <= scores[1]:
        raise RuntimeError("Reranker did not rank the reservation-cancellation candidate first.")

    result = {
        "device": torch.cuda.get_device_name(0),
        "cuda_available": True,
        "model_path": str(model_path),
        "load_and_rerank_ms": elapsed_ms,
        "scores": [round(float(score), 6) for score in scores],
        "relevant_candidate_rank": 1,
    }
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
