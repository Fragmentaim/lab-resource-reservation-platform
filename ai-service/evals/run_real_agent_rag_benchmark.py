"""Evaluate real Java Agent RAG tool execution against a Golden multiple-choice set.

The evaluator deliberately calls the public Java ``/knowledge/qa/ask`` API,
not Python retrieval helpers.  It therefore exercises authentication, ACL
document selection, native Function Calling, ``knowledge_search`` /
``knowledge_open_chunks``, context packing and the final model response.
Only answer letters and sanitized public trace metadata are persisted.
"""

from __future__ import annotations

import argparse
import getpass
import json
import re
import statistics
import time
from pathlib import Path
from typing import Any

import httpx


ANSWER_RE = re.compile(
    r"(?:答案|answer|正确选项|选项)\s*(?:是|为)?\s*[:：]?\s*[（(【\[]?\s*([ABCD])\b",
    re.IGNORECASE,
)


def main() -> int:
    args = parse_args()
    cases = read_jsonl(args.golden)
    if args.limit:
        cases = cases[:args.limit]
    if not cases:
        raise SystemExit("no Golden cases selected")

    document_ids = parse_document_ids(args.document_ids)
    missing_keys = sorted({
        str(evidence["document_key"])
        for case in cases
        for evidence in (case.get("expected_evidence") or [])
        if str(evidence.get("document_key") or "") not in document_ids
    })
    if missing_keys:
        raise ValueError(f"missing runtime document mapping: {', '.join(missing_keys)}")

    password = args.password or getpass.getpass("Backend password: ")
    output = args.output.resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    partial = output.with_suffix(output.suffix + ".partial")

    results: list[dict[str, Any]] = []
    with httpx.Client(timeout=args.timeout_seconds, trust_env=False) as client:
        token = login(client, args.base_url, args.username, password)
        headers = {"Authorization": f"Bearer {token}"}
        endpoint = f"{args.base_url.rstrip('/')}/knowledge/qa/ask"
        for index, case in enumerate(cases, start=1):
            result = run_case(client, endpoint, headers, case, document_ids)
            results.append(result)
            write_report(partial, args, results, partial=True)
            print(json.dumps({
                "completed": index,
                "benchmark_id": result["benchmark_id"],
                "answer_correct": result["answer_correct"],
                "tool_chain_completed": result["tool_chain_completed"],
                "expected_document_hit": result["expected_document_hit"],
                "error": result["error"],
            }, ensure_ascii=False), flush=True)

    write_report(output, args, results, partial=False)
    if partial.exists():
        partial.unlink()
    print(json.dumps(summarize(results), ensure_ascii=False, indent=2))
    print(output)
    return 0


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--golden", type=Path, required=True)
    parser.add_argument("--document-id", dest="document_ids", action="append", required=True,
                        help="Map Golden document key to the Java runtime document ID, e.g. D1=5")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--base-url", default="http://127.0.0.1:8085")
    parser.add_argument("--username", default="tester")
    parser.add_argument("--password", default=None, help="Prefer the hidden prompt over shell history.")
    parser.add_argument("--timeout-seconds", type=float, default=300.0)
    parser.add_argument("--limit", type=int, default=0)
    return parser.parse_args()


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def parse_document_ids(items: list[str]) -> dict[str, int]:
    mappings: dict[str, int] = {}
    for item in items:
        key, separator, value = item.partition("=")
        if not separator or not key.strip() or not value.strip().isdigit():
            raise ValueError(f"invalid --document-id: {item}; expected D1=5")
        mappings[key.strip()] = int(value.strip())
    return mappings


def login(client: httpx.Client, base_url: str, username: str, password: str) -> str:
    response = client.post(f"{base_url.rstrip('/')}/auth/login", json={"username": username, "password": password})
    body = response.json()
    if response.status_code >= 400 or body.get("code") != 200:
        raise RuntimeError(f"login failed: HTTP {response.status_code}")
    token = body.get("data", {}).get("token")
    if not isinstance(token, str) or not token:
        raise RuntimeError("login response did not contain a token")
    return token


def run_case(
    client: httpx.Client,
    endpoint: str,
    headers: dict[str, str],
    case: dict[str, Any],
    document_ids: dict[str, int],
) -> dict[str, Any]:
    expected_docs = {
        document_ids[str(evidence["document_key"])]
        for evidence in case.get("expected_evidence") or []
    }
    question = render_question(case)
    started = time.perf_counter()
    try:
        response = client.post(endpoint, headers=headers, json={"question": question})
        body = response.json()
        if response.status_code >= 400 or body.get("code") != 200:
            raise RuntimeError(f"ask failed: HTTP {response.status_code}")
        data = body.get("data") or {}
        calls = sanitized_tool_calls((data.get("contextStats") or {}).get("tool_calls"))
        tool_names = [call["tool_name"] for call in calls]
        source_documents = sorted({
            int(source.get("documentId"))
            for source in (data.get("sources") or [])
            if str(source.get("documentId") or "").isdigit()
        })
        predicted = extract_answer_letter(str(data.get("answer") or ""))
        return {
            "benchmark_id": case.get("benchmark_id"),
            "question_id": case.get("question_id"),
            "expected_answer": case.get("answer"),
            "predicted_answer": predicted,
            "answer_correct": predicted == case.get("answer"),
            "latency_ms": round((time.perf_counter() - started) * 1000, 2),
            "answer_nonblank": bool(str(data.get("answer") or "").strip()),
            "tool_calls": calls,
            "tool_names": tool_names,
            "tool_chain_completed": has_ordered_chain(tool_names, "knowledge_search", "knowledge_open_chunks"),
            "source_count": len(data.get("sources") or []),
            "source_document_ids": source_documents,
            "expected_document_ids": sorted(expected_docs),
            "expected_document_hit": bool(expected_docs.intersection(source_documents)),
            "runtime_managed": bool((data.get("contextStats") or {}).get("runtime_managed")),
            "trace_id": data.get("traceId"),
            "error": None,
        }
    except Exception as exc:
        return {
            "benchmark_id": case.get("benchmark_id"),
            "question_id": case.get("question_id"),
            "expected_answer": case.get("answer"),
            "predicted_answer": None,
            "answer_correct": False,
            "latency_ms": round((time.perf_counter() - started) * 1000, 2),
            "answer_nonblank": False,
            "tool_calls": [],
            "tool_names": [],
            "tool_chain_completed": False,
            "source_count": 0,
            "source_document_ids": [],
            "expected_document_ids": sorted(expected_docs),
            "expected_document_hit": False,
            "runtime_managed": False,
            "trace_id": None,
            "error": f"{type(exc).__name__}: {exc}",
        }


def render_question(case: dict[str, Any]) -> str:
    options = case.get("options") or {}
    option_text = "；".join(f"{key}. {value}" for key, value in options.items())
    return f"{case.get('question', '')}\n选项：{option_text}\n请检索知识库后，给出正确选项字母并简短说明依据。"


def sanitized_tool_calls(raw_calls: Any) -> list[dict[str, Any]]:
    if not isinstance(raw_calls, list):
        return []
    return [
        {
            "tool_name": str(call.get("tool_name") or ""),
            "result": str(call.get("result") or ""),
            "protocol": str(call.get("protocol") or ""),
            "latency_ms": number(call.get("latency_ms")),
        }
        for call in raw_calls
        if isinstance(call, dict)
    ]


def extract_answer_letter(answer: str) -> str | None:
    # Tool answers are Markdown in the public API, so normalize decoration
    # before extracting a single-choice label (e.g. ``**D**``).
    normalized = re.sub(r"[*`_#]", " ", answer)
    labeled_multiple = re.search(
        r"(?:答案|answer|正确选项|选项)\s*(?:是|为)?\s*[:：]?\s*[（(【\[]?\s*[ABCD]"
        r"\s*(?:、|,|，|和|及)\s*[ABCD]",
        normalized,
        re.IGNORECASE,
    )
    if labeled_multiple:
        return None
    match = ANSWER_RE.search(normalized)
    if match:
        return match.group(1).upper()
    standalone = re.findall(r"(?<![A-Za-z])([ABCD])(?![A-Za-z])", normalized.upper())
    return standalone[0] if len(standalone) == 1 else None


def has_ordered_chain(tool_names: list[str], first: str, second: str) -> bool:
    try:
        return tool_names.index(first) < tool_names.index(second)
    except ValueError:
        return False


def number(value: Any) -> int:
    try:
        return int(value)
    except (TypeError, ValueError):
        return 0


def rate(results: list[dict[str, Any]], key: str) -> float:
    return round(sum(bool(item.get(key)) for item in results) / len(results), 4) if results else 0.0


def summarize(results: list[dict[str, Any]]) -> dict[str, Any]:
    latencies = sorted(float(item["latency_ms"]) for item in results)

    def percentile(q: float) -> float:
        if not latencies:
            return 0.0
        return round(latencies[min(len(latencies) - 1, int((len(latencies) - 1) * q))], 2)

    grounded_success = [
        item["answer_correct"] and item["tool_chain_completed"] and item["expected_document_hit"]
        for item in results
    ]
    return {
        "case_count": len(results),
        "answer_accuracy": rate(results, "answer_correct"),
        "tool_chain_completion_rate": rate(results, "tool_chain_completed"),
        "source_coverage_rate": round(sum(item["source_count"] > 0 for item in results) / len(results), 4) if results else 0.0,
        "expected_document_hit_rate": rate(results, "expected_document_hit"),
        "grounded_task_success_rate": round(sum(grounded_success) / len(results), 4) if results else 0.0,
        "runtime_managed_rate": rate(results, "runtime_managed"),
        "request_success_rate": round(sum(item["error"] is None for item in results) / len(results), 4) if results else 0.0,
        "latency_ms": {
            "mean": round(statistics.mean(latencies), 2) if latencies else 0.0,
            "p50": percentile(0.50),
            "p95": percentile(0.95),
        },
        "scope": "Real Java Agent HTTP API with public documents. Metrics exclude raw answers, prompts, credentials and evidence text.",
    }


def write_report(path: Path, args: argparse.Namespace, results: list[dict[str, Any]], partial: bool) -> None:
    payload = {
        "benchmark_type": "real_java_agent_rag_tool_calling",
        "partial": partial,
        "golden_path": str(args.golden.resolve()),
        "runtime_document_ids": parse_document_ids(args.document_ids),
        "summary": summarize(results),
        "results": results,
    }
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")


if __name__ == "__main__":
    raise SystemExit(main())
