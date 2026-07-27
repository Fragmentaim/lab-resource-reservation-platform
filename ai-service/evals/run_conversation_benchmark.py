"""Run the fixed multi-turn conversation suite against the real Java backend.

This evaluator intentionally sends only user turns.  The assistant turns in the
fixture are reference annotations, not messages that should be injected into
the application session.  The report keeps answer text out of the artifact;
it records only sanitized signals, context accounting and tool names.
"""

from __future__ import annotations

import argparse
import getpass
import json
import statistics
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path
from typing import Any

import httpx


DEFAULT_ENDPOINT = "http://127.0.0.1:8083/knowledge/qa/ask"
EXPECTED_TOOLS = {
    "RESOURCE_AVAILABILITY": "resource_availability",
    "CANCELLATION_PREVIEW": "reservation_cancellation_preview",
}


def _built_in_suite() -> dict[str, Any]:
    """A reproducible 30-case suite aligned with the backend's conversation fixture.

    The evaluator sends only the user messages. Assistant replies are generated
    by the live Agent, so this remains an end-to-end session test rather than a
    prompt replay.
    """
    cases: list[dict[str, Any]] = []

    def shared_turns(setup: str, final_question: str) -> list[dict[str, str]]:
        users = [
            setup,
            "请记住我偏好下午，并且不要替我直接执行写操作。",
            "如果信息不足，请先说明缺少什么，不要猜测预约编号。",
            "我还需要遵守实验室权限和文档访问范围。",
            "之前的回答请保留关键的日期、资源和预约标识。",
            "接下来请继续按照当前对话上下文处理。",
            "这是较长会话，用于验证摘要后是否仍保留关键条件。",
            final_question,
        ]
        return [{"role": "user", "content": value} for value in users]

    for index in range(10):
        date = f"2026-07-{22 + index:02d}"
        cases.append({
            "caseId": f"date-period-{index + 1:02d}",
            "scenario": "先指定日期，后续仅说下午",
            "containsReference": True,
            "requiresSummary": index >= 5,
            "expectedFinalTask": "RESOURCE_AVAILABILITY",
            "expectedConstraints": {"date": date, "period": "下午", "resource": "显微镜"},
            "turns": shared_turns(f"我想在 {date} 预约显微镜", f"好，{date} 下午还有空吗？"),
        })
    resources = ["示波器", "3D打印机", "焊接台", "GPU服务器"]
    for index in range(8):
        first, second = resources[index % 4], resources[(index + 1) % 4]
        cases.append({
            "caseId": f"resource-switch-{index + 1:02d}",
            "scenario": "用户中途更换资源",
            "containsReference": True,
            "requiresSummary": index >= 5,
            "expectedFinalTask": "RESOURCE_AVAILABILITY",
            "expectedConstraints": {"resource": second, "replaced_resource": first},
            "turns": shared_turns(f"先查{first}的可用时段", f"不要{first}了，改查{second}刚才那个时段"),
        })
    for index in range(6):
        resource = "示波器" if index % 2 == 0 else "显微镜"
        cases.append({
            "caseId": f"ordinal-{index + 1:02d}",
            "scenario": "刚才那个、第二个等指代",
            "containsReference": True,
            "requiresSummary": index >= 2,
            "expectedFinalTask": "RESOURCE_AVAILABILITY",
            "expectedConstraints": {"resource": resource, "slot_reference": "第二个"},
            "turns": shared_turns(f"请列出{resource}的两个可预约时段", "就选第二个，帮我看看是否还可用"),
        })
    for index in range(6):
        reservation_id = 5001 + index
        cases.append({
            "caseId": f"reservation-cancel-{index + 1:02d}",
            "scenario": "先查询预约，后续要求取消",
            "containsReference": True,
            "requiresSummary": True,
            "expectedFinalTask": "CANCELLATION_PREVIEW",
            "expectedConstraints": {"reservationId": reservation_id, "writeExecuted": False},
            "turns": shared_turns(f"请查我的预约，重点看看编号 {reservation_id}", "把刚才那个预约取消前先帮我预检"),
        })
    return {"conversation_suite_version": "conversation-suite-v1", "conversation_cases": cases}


def _post_json(client: httpx.Client, url: str, headers: dict[str, str], payload: dict[str, Any]) -> dict[str, Any]:
    response = client.post(url, headers=headers, json=payload)
    body: Any
    try:
        body = response.json()
    except ValueError as exc:
        raise RuntimeError(f"non-json response: HTTP {response.status_code}") from exc
    if response.status_code >= 400 or not isinstance(body, dict) or body.get("code") != 200:
        message = body.get("message") if isinstance(body, dict) else None
        raise RuntimeError(f"request failed: HTTP {response.status_code}, {message or 'unknown error'}")
    data = body.get("data")
    return data if isinstance(data, dict) else {}


def _login(client: httpx.Client, base_url: str, username: str, password: str) -> str:
    data = _post_json(client, f"{base_url.rstrip('/')}/auth/login", {},
                      {"username": username, "password": password})
    token = data.get("token")
    if not isinstance(token, str) or not token.strip():
        raise RuntimeError("login response did not contain a token")
    return token


def _user_turns(case: dict[str, Any]) -> list[str]:
    """Render user turns without relying on model-generated filler.

    Long-context cases carry short, human-authored facts plus deterministic
    neutral meeting notes.  The notes make window pressure reproducible but do
    not repeat the facts scored at the final checkpoint.
    """
    user_turns = [turn for turn in case.get("turns", []) if turn.get("role") == "user"]
    padding_turns = [turn for turn in user_turns if _int(turn.get("paddingBlocks")) > 0]
    extra_padding_blocks = 0
    # The runner intentionally uses a dependency-free estimate here.  Keep a
    # small generation margin so the service-side tokenizer still reaches the
    # suite's declared minimum length.
    target_tokens = int(_int(case.get("targetUserInputTokens")) * 1.08)
    if target_tokens > 0 and padding_turns:
        baseline = _render_user_turns(case, user_turns, 0)
        baseline_tokens = sum(_estimate_tokens(message) for message in baseline)
        sample_block_tokens = _estimate_tokens(_neutral_context_notes(str(case.get("caseId") or "case"), 1, 1))
        deficit = max(0, target_tokens - baseline_tokens)
        extra_padding_blocks = (deficit + len(padding_turns) * sample_block_tokens - 1) // (
            len(padding_turns) * sample_block_tokens
        )
    return _render_user_turns(case, user_turns, extra_padding_blocks)


def _render_user_turns(case: dict[str, Any], user_turns: list[dict[str, Any]], extra_padding_blocks: int) -> list[str]:
    rendered: list[str] = []
    for turn_index, turn in enumerate(user_turns, start=1):
        content = str(turn.get("content") or "").strip()
        if not content:
            continue
        padding_blocks = _int(turn.get("paddingBlocks")) + extra_padding_blocks
        if padding_blocks > 0:
            content = f"{content}\n\n{_neutral_context_notes(str(case.get('caseId') or 'case'), turn_index, padding_blocks)}"
        rendered.append(content)
    return rendered


def _estimate_tokens(text: str) -> int:
    chinese_chars = sum(1 for char in text if "\u4e00" <= char <= "\u9fff")
    return max(1, int(chinese_chars * 1.2 + max(0, len(text) - chinese_chars) / 4))


def _neutral_context_notes(case_id: str, turn_index: int, blocks: int) -> str:
    lines = ["以下是本轮附带的实验室沟通纪要，仅用于保留长会话中的自然背景，不改变本轮的预约目标："]
    for block_index in range(1, blocks + 1):
        sequence = f"{case_id}-{turn_index:02d}-{block_index:02d}"
        lines.append(
            f"纪要 {sequence}：本次讨论记录了设备清洁、值班交接、网络巡检、耗材盘点和安全培训的常规进度。"
            "相关事项由对应负责人后续跟进；这段纪要不包含预约编号、实验室选择、时间偏好、权限结论或取消指令。"
        )
    return "\n".join(lines)


def _int(value: Any, default: int = 0) -> int:
    try:
        return int(value)
    except (TypeError, ValueError):
        return default


def _usage(value: Any) -> dict[str, Any]:
    raw = value if isinstance(value, dict) else {}
    return {
        "reported": bool(raw.get("reported")),
        "model_round_count": _int(raw.get("model_round_count")),
        "agent_model_round_count": _int(raw.get("agent_model_round_count")),
        "summary_call_count": _int(raw.get("summary_call_count")),
        "input_tokens": _int(raw.get("input_tokens")),
        "output_tokens": _int(raw.get("output_tokens")),
        "total_tokens": _int(raw.get("total_tokens")),
        "cached_input_tokens": _int(raw.get("cached_input_tokens")),
    }


def _plan(stats: Any) -> dict[str, Any]:
    if not isinstance(stats, dict):
        return {}
    value = stats.get("session_context_plan")
    return value if isinstance(value, dict) else {}


def _tool_calls(stats: Any) -> list[dict[str, Any]]:
    if not isinstance(stats, dict) or not isinstance(stats.get("tool_calls"), list):
        return []
    calls: list[dict[str, Any]] = []
    for raw in stats["tool_calls"]:
        if isinstance(raw, dict):
            # Java's public trace is already sanitized: retain names and safe
            # result metadata, never copy arbitrary model arguments.
            calls.append({
                "tool_name": str(raw.get("tool_name") or ""),
                "result": str(raw.get("result") or ""),
                "protocol": str(raw.get("protocol") or ""),
                "latency_ms": _int(raw.get("latency_ms")),
            })
            if isinstance(raw.get("evaluation_arguments"), dict):
                calls[-1]["evaluation_arguments"] = raw["evaluation_arguments"]
    return calls


def _tool_names(calls: list[dict[str, Any]]) -> list[str]:
    return [call["tool_name"] for call in calls if call.get("tool_name")]


def _required_tool_coverage(case: dict[str, Any], turns: list[dict[str, Any]]) -> tuple[bool, dict[str, dict[str, int | bool]]]:
    expected = case.get("expectedToolMinimumCounts")
    if not isinstance(expected, dict) or not expected:
        return True, {}
    observed_names = [
        name
        for turn in turns
        for name in _tool_names(turn.get("tool_calls") or [])
    ]
    coverage: dict[str, dict[str, int | bool]] = {}
    all_satisfied = True
    for raw_name, raw_minimum in expected.items():
        name = str(raw_name)
        minimum = max(0, _int(raw_minimum))
        observed = observed_names.count(name)
        satisfied = observed >= minimum
        coverage[name] = {
            "minimum": minimum,
            "observed": observed,
            "satisfied": satisfied,
        }
        all_satisfied &= satisfied
    return all_satisfied, coverage


def _text_signals(answer: str, calls: list[dict[str, Any]]) -> str:
    # Only for scoring.  The raw answer is never written to the report.
    return answer + " " + json.dumps(calls, ensure_ascii=False)


def _constraint_match(key: str, expected: Any, haystack: str, calls: list[dict[str, Any]]) -> bool:
    if key == "writeExecuted":
        return not any(name in {"reservation_create", "reservation_cancel"} for name in _tool_names(calls))
    if expected is None:
        return False
    return str(expected).lower() in haystack.lower()


def _case_result(case: dict[str, Any], turns: list[dict[str, Any]], error: str | None) -> dict[str, Any]:
    expected = case.get("expectedConstraints") or {}
    final = turns[-1] if turns else {}
    final_calls = final.get("tool_calls") or []
    final_names = set(_tool_names(final_calls))
    expected_task = str(case.get("expectedFinalTask") or "")
    expected_tool = EXPECTED_TOOLS.get(expected_task)
    answer_nonblank = bool(final.get("answer_nonblank"))
    task_tool_ok = expected_tool is None or expected_tool in final_names
    required_tools_ok, required_tool_coverage = _required_tool_coverage(case, turns)
    retained = {
        key: _constraint_match(key, value, str(final.get("scoring_text") or ""), final_calls)
        for key, value in expected.items()
    }
    if expected_task == "RESOURCE_AVAILABILITY":
        critical_keys = [key for key in ("date", "period", "resource", "slot_reference") if key in expected]
    elif expected_task == "CANCELLATION_PREVIEW":
        critical_keys = [key for key in ("reservationId", "writeExecuted") if key in expected]
    else:
        critical_keys = list(expected)
    critical_retained = all(retained.get(key, False) for key in critical_keys)
    # This is deliberately conservative: a non-empty answer without the
    # expected business tool is not counted as a completed business task.
    task_success = error is None and answer_nonblank and task_tool_ok and required_tools_ok and critical_retained
    summary_turn = next((turn for turn in turns if turn.get("summary_triggered")), None)
    return {
        "case_id": case.get("caseId"),
        "scenario": case.get("scenario"),
        "contains_reference": bool(case.get("containsReference")),
        "requires_summary": bool(case.get("requiresSummary")),
        "expected_final_task": expected_task,
        "turn_count": len(turns),
        "turns": [
            {
                key: turn[key]
                for key in (
                    "turn_index", "latency_ms", "trace_id", "answer_nonblank",
                    "tool_names", "context_stats_keys", "route", "runtime_managed",
                    "history_tokens", "summary_tokens", "deferred_turn_count",
                    "summary_triggered", "provider_usage",
                )
                if key in turn
            }
            for turn in turns
        ],
        "retained_constraints": retained,
        "retained_constraint_count": sum(1 for value in retained.values() if value),
        "constraint_count": len(retained),
        "required_tool_coverage": required_tool_coverage,
        "required_tools_satisfied": required_tools_ok,
        "reference_resolved": task_tool_ok and required_tools_ok and answer_nonblank and critical_retained,
        "final_task_succeeded": task_success,
        "summary_triggered": summary_turn is not None,
        "history_tokens_before_summary": _int(summary_turn.get("history_tokens_before")) if summary_turn else 0,
        "history_tokens_after_summary": _int(summary_turn.get("history_tokens")) if summary_turn else 0,
        "task_before_summary_succeeded": False,
        "task_after_summary_succeeded": task_success if summary_turn else False,
        "error": error,
        "scope_note": "Real Java HTTP session; task success is conservatively inferred from final answer, public tool trace and expected constraint signals.",
    }


def _run_case(client: httpx.Client, endpoint: str, token: str, case: dict[str, Any]) -> dict[str, Any]:
    session_id: str | None = None
    turns: list[dict[str, Any]] = []
    previous_plan: dict[str, Any] = {}
    try:
        headers = {"Authorization": f"Bearer {token}"}
        for index, question in enumerate(_user_turns(case), start=1):
            payload: dict[str, Any] = {"question": question}
            if session_id:
                payload["sessionId"] = session_id
            started = time.perf_counter()
            data: dict[str, Any] | None = None
            last_error: Exception | None = None
            for attempt in range(3):
                try:
                    data = _post_json(client, endpoint, headers, payload)
                    break
                except RuntimeError as exc:
                    last_error = exc
                    message = str(exc)
                    transient = any(marker in message for marker in (
                        "会话上下文正在更新", "HTTP 429", "HTTP 502", "HTTP 503", "HTTP 504"
                    ))
                    if not transient or attempt == 2:
                        raise
                    time.sleep(0.5 * (attempt + 1))
            if data is None:
                raise last_error or RuntimeError("request did not return data")
            elapsed_ms = round((time.perf_counter() - started) * 1000, 2)
            session_id = str(data.get("sessionId") or session_id or "")
            stats = data.get("contextStats")
            plan = _plan(stats)
            calls = _tool_calls(stats)
            answer = str(data.get("answer") or "")
            summary_tokens = _int(plan.get("summary_tokens"))
            provider_usage = _usage(stats.get("provider_usage") if isinstance(stats, dict) else None)
            # Summary text may already exist from an earlier compaction, so a
            # before/after token comparison misses later real LLM summaries.
            # The backend reports this per response from the actual provider call.
            summary_triggered = provider_usage["summary_call_count"] > 0
            turns.append({
                "turn_index": index,
                "latency_ms": elapsed_ms,
                "trace_id": data.get("traceId"),
                "answer_nonblank": bool(answer.strip()),
                "tool_names": _tool_names(calls),
                "tool_calls": calls,
                "context_stats_keys": sorted(stats.keys()) if isinstance(stats, dict) else [],
                "route": stats.get("route") if isinstance(stats, dict) else None,
                "runtime_managed": stats.get("runtime_managed") if isinstance(stats, dict) else None,
                "history_tokens": _int(plan.get("history_tokens")),
                "summary_tokens": summary_tokens,
                "deferred_turn_count": _int(plan.get("deferred_turn_count")),
                "summary_triggered": summary_triggered,
                "history_tokens_before": _int(previous_plan.get("history_tokens")) if summary_triggered else 0,
                "provider_usage": provider_usage,
                "scoring_text": _text_signals(answer, calls),
            })
            previous_plan = plan
        return _case_result(case, turns, None)
    except Exception as exc:  # keep one failed case from losing the checkpoint
        return _case_result(case, turns, str(exc))


def _summary(results: list[dict[str, Any]], model_profile: str | None) -> dict[str, Any]:
    cases = len(results)
    expected = sum(_int(item.get("constraint_count")) for item in results)
    retained = sum(_int(item.get("retained_constraint_count")) for item in results)
    reference_cases = sum(1 for item in results if item.get("contains_reference"))
    reference_ok = sum(1 for item in results if item.get("contains_reference") and item.get("reference_resolved"))
    successful = sum(1 for item in results if item.get("final_task_succeeded"))
    summary_cases = sum(1 for item in results if item.get("requires_summary"))
    summary_triggered = sum(1 for item in results if item.get("requires_summary") and item.get("summary_triggered"))
    summary_results = [item for item in results if item.get("requires_summary") and item.get("summary_triggered")]
    before = sum(_int(item.get("history_tokens_before_summary")) for item in summary_results)
    after = sum(_int(item.get("history_tokens_after_summary")) for item in summary_results)
    latencies = [float(turn.get("latency_ms")) for item in results for turn in item.get("turns", [])]
    usages = [_usage(turn.get("provider_usage")) for item in results for turn in item.get("turns", [])]
    latencies.sort()

    def percentile(q: float) -> float:
        if not latencies:
            return 0.0
        return latencies[min(len(latencies) - 1, int((len(latencies) - 1) * q))]

    return {
        "conversation_count": cases,
        "model_profile": model_profile,
        "key_constraint_retention_rate": round(retained / expected, 4) if expected else 0.0,
        "reference_resolution_accuracy": round(reference_ok / reference_cases, 4) if reference_cases else 0.0,
        "multi_turn_task_success_rate": round(successful / cases, 4) if cases else 0.0,
        "summary_case_count": summary_cases,
        "summary_triggered_count": summary_triggered,
        "summary_trigger_rate": round(summary_triggered / summary_cases, 4) if summary_cases else 0.0,
        "history_tokens_before_summary": before,
        "history_tokens_after_summary": after,
        "history_token_reduction_rate": round(1 - after / before, 4) if before else 0.0,
        "turn_latency_ms": {
            "mean": round(statistics.mean(latencies), 2) if latencies else 0.0,
            "p50": percentile(0.50),
            "p95": percentile(0.95),
            "p99": percentile(0.99),
        },
        "provider_usage": {
            "reported_turn_count": sum(1 for usage in usages if usage["reported"]),
            "model_round_count": sum(usage["model_round_count"] for usage in usages),
            "agent_model_round_count": sum(usage["agent_model_round_count"] for usage in usages),
            "summary_call_count": sum(usage["summary_call_count"] for usage in usages),
            "input_tokens": sum(usage["input_tokens"] for usage in usages),
            "output_tokens": sum(usage["output_tokens"] for usage in usages),
            "total_tokens": sum(usage["total_tokens"] for usage in usages),
            "cached_input_tokens": sum(usage["cached_input_tokens"] for usage in usages),
        },
        "failed_cases": [item.get("case_id") for item in results if item.get("error")],
        "scope": "Real Java session API; no raw answers, credentials or evidence text are written.",
    }


def _write(path: Path, suite: dict[str, Any], results: list[dict[str, Any]], partial: bool,
           model_profile: str | None, context_strategy: str | None) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    payload = {
        "suite_version": suite.get("conversation_suite_version"),
        "benchmark_type": "real_java_multi_turn_context",
        "context_strategy": context_strategy,
        "partial": partial,
        "summary": _summary(results, model_profile),
        "results": results,
    }
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description="Run the fixed multi-turn Agent conversation suite against Java.")
    parser.add_argument("--suite", type=Path, help="Optional external suite JSON; omit to use the built-in 30-case suite.")
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--base-url", default="http://127.0.0.1:8083")
    parser.add_argument("--endpoint", default=None)
    parser.add_argument("--username", default="tester")
    parser.add_argument("--password", default=None, help="Avoid this in shell history; prefer the hidden prompt.")
    parser.add_argument("--model-profile", default=None)
    parser.add_argument("--context-strategy", default=None,
                        help="Report label only, e.g. FULL_HISTORY, SLIDING_WINDOW or SUMMARY.")
    parser.add_argument("--limit", type=int, default=0)
    parser.add_argument("--case-ids", default="")
    parser.add_argument("--concurrency", type=int, default=1,
                        help="Independent conversation sessions to run concurrently.")
    parser.add_argument("--timeout-seconds", type=float, default=240.0)
    args = parser.parse_args()

    suite = json.loads(args.suite.read_text(encoding="utf-8")) if args.suite else _built_in_suite()
    cases = list(suite.get("conversation_cases") or [])
    if args.case_ids.strip():
        selected = {value.strip() for value in args.case_ids.split(",") if value.strip()}
        cases = [case for case in cases if case.get("caseId") in selected]
    if args.limit:
        cases = cases[:args.limit]
    if not cases:
        raise SystemExit("no conversation cases selected")

    password = args.password if args.password is not None else getpass.getpass("Backend password: ")
    endpoint = args.endpoint or f"{args.base_url.rstrip('/')}/knowledge/qa/ask"
    partial_path = args.output.with_suffix(args.output.suffix + ".partial")
    results: list[dict[str, Any]] = []
    with httpx.Client(timeout=max(1.0, args.timeout_seconds), trust_env=False) as client:
        token = _login(client, args.base_url, args.username, password)
        if args.concurrency <= 1:
            ordered_results = []
            for case in cases:
                ordered_results.append(_run_case(client, endpoint, token, case))
                results = list(ordered_results)
                _write(partial_path, suite, results, True, args.model_profile, args.context_strategy)
                result = ordered_results[-1]
                print(json.dumps({"completed": len(results), "case_id": result.get("case_id"),
                                  "summary_triggered": result.get("summary_triggered"),
                                  "task_success": result.get("final_task_succeeded"),
                                  "error": result.get("error")}, ensure_ascii=False), flush=True)
        else:
            completed: list[dict[str, Any] | None] = [None] * len(cases)
            with ThreadPoolExecutor(max_workers=max(1, args.concurrency),
                                    thread_name_prefix="conversation-eval") as pool:
                futures = {
                    pool.submit(_run_case, client, endpoint, token, case): index
                    for index, case in enumerate(cases)
                }
                for future in as_completed(futures):
                    index = futures[future]
                    completed[index] = future.result()
                    results = [item for item in completed if item is not None]
                    _write(partial_path, suite, results, True, args.model_profile, args.context_strategy)
                    result = completed[index]
                    print(json.dumps({"completed": len(results), "case_id": result.get("case_id"),
                                      "summary_triggered": result.get("summary_triggered"),
                                      "task_success": result.get("final_task_succeeded"),
                                      "error": result.get("error")}, ensure_ascii=False), flush=True)
    _write(args.output, suite, results, False, args.model_profile, args.context_strategy)
    if partial_path.exists():
        partial_path.unlink()
    print(json.dumps(json.loads(args.output.read_text(encoding="utf-8"))["summary"], ensure_ascii=False, indent=2))
    print(args.output.resolve())
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
