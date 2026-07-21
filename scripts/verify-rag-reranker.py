"""Verify the production local GPU reranker endpoint with a deterministic pair."""

from __future__ import annotations

import argparse
import json
import os
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen


def request_json(url: str, payload: dict | None = None, api_key: str = "") -> dict:
    data = json.dumps(payload).encode("utf-8") if payload is not None else None
    headers = {"Accept": "application/json"}
    if data is not None:
        headers["Content-Type"] = "application/json"
    if api_key:
        headers["Authorization"] = f"Bearer {api_key}"
    request = Request(url, data=data, headers=headers, method="POST" if data is not None else "GET")
    try:
        with urlopen(request, timeout=90) as response:
            return json.loads(response.read().decode("utf-8"))
    except (HTTPError, URLError) as error:
        raise RuntimeError(f"Local reranker request failed: {error}") from error


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://127.0.0.1:8011")
    parser.add_argument("--api-key", default=os.getenv("LOCAL_RERANKER_API_KEY", "lab-local-reranker"))
    args = parser.parse_args()

    base_url = args.base_url.rstrip("/")
    health = request_json(f"{base_url}/healthz")
    if not health.get("cuda_available"):
        raise RuntimeError("The local reranker cannot see CUDA.")

    result = request_json(
        f"{base_url}/v1/rerank",
        {
            "model": "Qwen3-Reranker-0.6B",
            "query": "实验室预约提交后如何取消？",
            "documents": [
                "用户只能取消自己的、仍为 BOOKED 的预约。取消后会恢复时段配额。",
                "设备使用前应检查防护装置和电源状态。",
            ],
            "top_n": 2,
            "return_documents": False,
        },
        args.api_key,
    )
    ranked = result.get("results") or []
    if len(ranked) != 2 or ranked[0].get("index") != 0:
        raise RuntimeError(f"Unexpected rerank order: {ranked}")

    print(json.dumps({
        "endpoint": base_url,
        "cuda_available": health.get("cuda_available"),
        "model_loaded": health.get("model_loaded"),
        "relevant_candidate_rank": 1,
        "scores": [item.get("relevance_score") for item in ranked],
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
