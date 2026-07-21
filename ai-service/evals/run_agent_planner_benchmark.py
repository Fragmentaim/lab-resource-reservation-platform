#!/usr/bin/env python3
"""Run the fixed Agent planner suite against the real Function Calling endpoint.

This benchmark intentionally isolates the model-planning contract: model calls are
real, while tool outputs are deterministic fixtures. It measures whether the
model selects declared tools and supplies the required arguments. It must never
be reported as an end-to-end reservation or ACL result; those require the
separate isolated Java integration environment and persisted AgentRun/Step data.
"""

from __future__ import annotations

import argparse
import json
import statistics
import time
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from typing import Any

import httpx


TOOL_DEFINITIONS = [
    {
        "type": "function",
        "function": {
            "name": "knowledge_search",
            "description": "Search document chunks the current user may access.",
            "parameters": {
                "type": "object",
                "properties": {"query": {"type": "string"}},
                "required": ["query"],
                "additionalProperties": False,
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "knowledge_open_chunks",
            "description": "Read authorized candidate chunks returned by knowledge_search.",
            "parameters": {
                "type": "object",
                "properties": {"chunkUids": {"type": "array", "items": {"type": "string"}, "minItems": 1}},
                "required": ["chunkUids"],
                "additionalProperties": False,
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "reservation_context",
            "description": "Read the current user's reservations only.",
            "parameters": {"type": "object", "properties": {}, "additionalProperties": False},
        },
    },
    {
        "type": "function",
        "function": {
            "name": "resource_availability",
            "description": "Query a laboratory resource's available slots.",
            "parameters": {
                "type": "object",
                "properties": {"keyword": {"type": "string"}},
                "required": [],
                "additionalProperties": False,
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "reservation_cancellation_preview",
            "description": "Preview whether one of the current user's reservations can be cancelled. Never execute cancellation.",
            "parameters": {
                "type": "object",
                "properties": {"reservationId": {"type": "integer"}},
                "required": ["reservationId"],
                "additionalProperties": False,
            },
        },
    },
]


def _tool_output(name: str, arguments: dict[str, Any], acl_allowed: bool) -> dict[str, Any]:
    """Return safe, deterministic stub output without embedding answer keys."""
    if name == "knowledge_search":
        if not acl_allowed:
            return {"status": "NO_ACCESSIBLE_DOCUMENTS", "candidates": []}
        return {
            "status": "OK",
            "candidates": [
                {"chunk_uid": "candidate-1", "document_id": "authorized-doc", "title": "Authorized evidence"},
                {"chunk_uid": "candidate-2", "document_id": "authorized-doc", "title": "Authorized follow-up"},
            ],
        }
    if name == "knowledge_open_chunks":
        requested = arguments.get("chunkUids") or ["candidate-1"]
        return {
            "status": "OK" if acl_allowed else "NO_ACCESSIBLE_DOCUMENTS",
            "chunks": ([{"chunk_uid": item, "content": "Authorized evidence is available for this query."} for item in requested]
                       if acl_allowed else []),
        }
    if name == "reservation_context":
        return {
            "status": "OK",
            "activeReservationCount": 1,
            "upcomingReservations": [{"reservationId": "reservation-001", "resourceName": "实验室A", "date": "2026-07-25"}],
        }
    if name == "resource_availability":
        return {
            "status": "OK",
            "keyword": arguments.get("keyword", "实验室A"),
            "slots": ["09:00-10:00", "14:00-15:00"],
        }
    if name == "reservation_cancellation_preview":
        return {
            "status": "OK",
            "reservationId": arguments.get("reservationId", 5001),
            "canCancel": True,
            "writeExecuted": False,
        }
    return {"status": "UNSUPPORTED_TOOL"}


def _identity_context(case: dict[str, Any]) -> dict[str, Any]:
    identity = case.get("actor") or {}
    permissions = [identity.get("role", "USER")]
    history = case.get("history") or []
    memory = "当前用户身份：{}；权限：{}。".format(
        identity.get("userId", "eval-user"),
        ", ".join(permissions) if permissions else "普通用户",
    )
    return {"working_memory": memory, "history": history}


def _required_arguments_match(expected: dict[str, Any], actual: dict[str, Any]) -> bool:
    for key, expected_value in expected.items():
        if not _value_matches(expected_value, actual.get(key)):
            return False
    return True


def _value_matches(expected: Any, actual: Any) -> bool:
    if expected == "$ANY_AUTHORIZED_CANDIDATE":
        return isinstance(actual, str) and bool(actual.strip())
    if isinstance(expected, list):
        if len(expected) == 1 and expected[0] == "$ANY_AUTHORIZED_CANDIDATE":
            return isinstance(actual, list) and bool(actual) and all(
                isinstance(value, str) and bool(value.strip()) for value in actual
            )
        return isinstance(actual, list) and len(expected) == len(actual) and all(
            _value_matches(left, right) for left, right in zip(expected, actual)
        )
    if isinstance(expected, (int, float)) and isinstance(actual, (int, float)):
        return expected == actual
    return expected == actual


def _evaluate_case(client: httpx.Client, endpoint: str, token: str, case: dict[str, Any], model: str | None, max_rounds: int) -> dict[str, Any]:
    expected_calls = case.get("expectedToolCalls") or []
    expected_tools = [item.get("toolName") for item in expected_calls]
    expected_parameters = [item.get("arguments") or {} for item in expected_calls]
    acl_allowed = (case.get("expectedBusinessResult") or {}).get("knowledge_status") != "NO_ACCESSIBLE_DOCUMENTS"
    executed_calls: list[dict[str, Any]] = []
    observed_calls: list[dict[str, Any]] = []
    round_latencies: list[float] = []
    final_answer = ""
    error: str | None = None

    for _ in range(max_rounds):
        payload = {
            "question": case["question"],
            "tools": TOOL_DEFINITIONS,
            "executed_calls": executed_calls,
            "conversation_context": _identity_context(case),
        }
        if model:
            payload["model"] = model
        started = time.perf_counter()
        try:
            response = client.post(endpoint, headers={"X-AI-Service-Token": token}, json=payload)
            response.raise_for_status()
            body = response.json()
        except (httpx.HTTPError, ValueError) as exc:
            error = str(exc)
            break
        round_latencies.append((time.perf_counter() - started) * 1000)
        calls = body.get("tool_calls") or []
        if not calls:
            final_answer = (body.get("answer") or "").strip()
            break
        for call in calls:
            name = call.get("name", "")
            arguments = call.get("arguments") if isinstance(call.get("arguments"), dict) else {}
            observed_calls.append({"name": name, "arguments": arguments})
            executed_calls.append({
                "call_id": call.get("call_id") or f"eval-{len(executed_calls) + 1}",
                "name": name,
                "arguments": arguments,
                "output": _tool_output(name, arguments, acl_allowed),
            })

    actual_names = [item["name"] for item in observed_calls]
    selection_ok = actual_names == expected_tools
    parameter_ok = selection_ok and len(observed_calls) == len(expected_parameters)
    if parameter_ok:
        parameter_ok = all(_required_arguments_match(expected, call["arguments"])
                           for expected, call in zip(expected_parameters, observed_calls))
    direct_case = not expected_tools
    clarification_expected = (case.get("expectedBusinessResult") or {}).get("route") == "CLARIFICATION_REQUIRED"
    answer_ok = (not direct_case) or bool(final_answer) or bool(observed_calls)
    if clarification_expected:
        answer_ok = direct_case and not observed_calls and bool(final_answer)
    false_tools = 1 if not expected_tools and actual_names else 0
    return {
        "case_id": case.get("caseId", case.get("case_id")),
        "category": _category(case),
        "expected_tools": expected_tools,
        "actual_tools": actual_names,
        "expected_parameters": expected_parameters,
        "actual_calls": observed_calls,
        "selection_ok": selection_ok,
        "parameters_ok": parameter_ok,
        "answer_contract_ok": answer_ok,
        "false_tool_count": false_tools,
        "step_count": len(observed_calls),
        "round_latencies_ms": round_latencies,
        "provider_usage": body.get("provider_usage", {}) if error is None else {},
        "error": error,
        "note": "Real model planning with deterministic simulated tool outputs; not end-to-end business completion.",
    }


def _summarize(results: list[dict[str, Any]]) -> dict[str, Any]:
    count = len(results)
    category = Counter(item.get("category") for item in results)
    latencies = [latency for item in results for latency in item.get("round_latencies_ms", [])]
    return {
        "case_count": count,
        "category_counts": dict(sorted(category.items())),
        "tool_selection_accuracy": round(sum(item["selection_ok"] for item in results) / count, 4) if count else 0,
        "parameter_accuracy": round(sum(item["parameters_ok"] for item in results) / count, 4) if count else 0,
        "answer_contract_accuracy": round(sum(item["answer_contract_ok"] for item in results) / count, 4) if count else 0,
        "false_tool_rate": round(sum(item["false_tool_count"] for item in results) / count, 4) if count else 0,
        "average_steps": round(statistics.mean(item["step_count"] for item in results), 4) if count else 0,
        "model_round_count": len(latencies),
        "model_round_latency_ms": {
            "mean": round(statistics.mean(latencies), 2) if latencies else None,
            "p50": round(_percentile(latencies, 0.50), 2) if latencies else None,
            "p95": round(_percentile(latencies, 0.95), 2) if latencies else None,
        },
        "failed_cases": [item["case_id"] for item in results if item.get("error")],
        "scope": "Planner contract only: no real reservation write, retrieval index, ACL datastore, or AgentRun persistence was exercised.",
    }


def _percentile(values: list[float], quantile: float) -> float:
    if not values:
        return 0
    ordered = sorted(values)
    index = max(0, min(len(ordered) - 1, int((len(ordered) - 1) * quantile)))
    return ordered[index]


def _category(case: dict[str, Any]) -> str:
    case_id = case.get("caseId", "")
    if case_id.startswith("direct-"):
        return "DIRECT_NO_TOOL"
    if case_id.startswith("knowledge-"):
        return "RAG"
    if case_id.startswith("reservation-"):
        return "PERSONAL_RESERVATIONS"
    if case_id.startswith("availability-"):
        return "RESOURCE_AVAILABILITY"
    if case_id.startswith("cancel-preview-"):
        return "CANCEL_PREVIEW"
    if case_id.startswith("multi-"):
        return "MULTI_TOOL"
    if case_id.startswith("clarify-"):
        return "MISSING_AMBIGUOUS"
    if case_id.startswith("acl-"):
        return "ACL_ADVERSARIAL"
    return "UNKNOWN"


def main() -> int:
    parser = argparse.ArgumentParser(description="Run the fixed Java-exported Agent planner suite against FastAPI.")
    parser.add_argument("--suite", required=True, type=Path, help="JSON written by AgentEvaluationFixtureExporter")
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--endpoint", default="http://127.0.0.1:8005/api/v1/ai/tool-calling/round")
    parser.add_argument("--token", required=True, help="AI service internal token; do not put it in the output.")
    parser.add_argument("--model", default=None)
    parser.add_argument("--max-rounds", type=int, default=4)
    parser.add_argument("--concurrency", type=int, default=1,
                        help="Independent cases to execute concurrently; use 1 for deterministic provider debugging.")
    parser.add_argument("--limit", type=int, default=0, help="Run only the first N cases for smoke verification.")
    args = parser.parse_args()

    fixture = json.loads(args.suite.read_text(encoding="utf-8"))
    cases = fixture["task_cases"]
    if args.limit:
        cases = cases[:args.limit]
    with httpx.Client(timeout=120.0) as client:
        if args.concurrency <= 1:
            results = [_evaluate_case(client, args.endpoint, args.token, case, args.model, args.max_rounds) for case in cases]
        else:
            with ThreadPoolExecutor(max_workers=args.concurrency, thread_name_prefix="agent-eval") as pool:
                results = list(pool.map(
                    lambda case: _evaluate_case(client, args.endpoint, args.token, case, args.model, args.max_rounds),
                    cases,
                ))
    output = {
        "suite_version": fixture.get("task_suite_version"),
        "benchmark_type": "real_model_planner_contract",
        "model": args.model,
        "concurrency": args.concurrency,
        "summary": _summarize(results),
        "results": results,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(output, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(output["summary"], ensure_ascii=False, indent=2))
    print(args.output.resolve())
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
