"""Independently audit whether candidate Golden evidence supports its answer.

This is an evidence gate, not an answer benchmark: the judge sees only the
candidate's cited chunks and must declare them sufficient for the labelled
answer.  A case is promoted only when every configured judge returns a valid,
supported verdict that agrees with the label.
"""

from __future__ import annotations

import argparse
import json
import os
import re
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path
from typing import Any

from bootstrap_golden_from_mcq import (
    DEFAULT_MODELS,
    OpenAICompatibleJudge,
    build_chunks,
    read_jsonl,
    render_context,
    write_json,
    write_jsonl,
)


def main() -> None:
    args = parse_args()
    api_key = os.environ.get("TOKENMP_API_KEY", "").strip()
    if not api_key:
        raise SystemExit("TOKENMP_API_KEY is required")

    candidates = read_jsonl(args.golden)
    chunks = build_chunks(args.corpus_dir)
    chunks_by_id = {item["chunk_id"]: item for item in chunks}
    output_dir = args.output_dir.resolve()
    output_dir.mkdir(parents=True, exist_ok=True)
    client = OpenAICompatibleJudge(args.api_base_url, api_key, args.timeout_seconds, args.reasoning_effort)

    tasks = [(model, case) for model in args.models for case in candidates]
    results: list[dict[str, Any]] = []
    raw_responses: list[dict[str, Any]] = []
    with ThreadPoolExecutor(max_workers=args.concurrency) as executor:
        futures = {
            executor.submit(audit_case, client, model, case, chunks_by_id, args.max_output_tokens): (model, case)
            for model, case in tasks
        }
        for future in as_completed(futures):
            model, case = futures[future]
            record, raw = future.result()
            results.append(record)
            raw_responses.append({"benchmark_id": case["benchmark_id"], "model": model, **raw})
            print(
                f"audit question={case['question_id']} model={model} "
                f"pass={record.get('pass')} supported={record.get('supported')}",
                flush=True,
            )

    promoted, report = summarize(candidates, args.models, results)
    write_jsonl(output_dir / "evidence_audit_results.jsonl", results)
    write_jsonl(output_dir / "evidence_audit_raw_responses.jsonl", raw_responses)
    write_jsonl(output_dir / "robomaster_golden_v1.jsonl", promoted)
    write_json(output_dir / "evidence_audit_report.json", report)
    print(json.dumps(report["summary"], ensure_ascii=False, indent=2))


def audit_case(
    client: OpenAICompatibleJudge,
    model: str,
    case: dict[str, Any],
    chunks_by_id: dict[str, dict[str, Any]],
    max_output_tokens: int,
) -> tuple[dict[str, Any], dict[str, Any]]:
    selected = []
    missing = []
    for evidence in case.get("expected_evidence") or []:
        chunk = chunks_by_id.get(evidence["offline_chunk_id"])
        if chunk is None:
            missing.append(evidence["offline_chunk_id"])
        else:
            selected.append(chunk)
    if missing:
        return failed(case, model, f"missing evidence chunks: {', '.join(missing)}")

    options = "\n".join(f"{key}. {value}" for key, value in case["options"].items())
    system = (
        "你是严格的 RAG 证据审计员。只能依据提供的证据，核验标注答案是否能被完整支持。"
        "逐项检查题干中的条件和选项，不得使用外部常识。"
        "仅输出 JSON："
        '{"answer_from_evidence":"A","supported":true,"missing_claims":[],"reason":"不超过80字"}'
    )
    user = (
        f"题目：{case['question']}\n{options}\n"
        f"标注答案：{case['answer']}\n\n候选证据：\n{render_context(selected)}"
    )
    try:
        raw, usage, finish_reason = client.ask(model, system, user, max_output_tokens)
        payload = parse_object(raw)
        answer = str(payload.get("answer_from_evidence", "")).upper().strip()
        answer = answer if answer in {"A", "B", "C", "D"} else None
        supported = payload.get("supported") is True
        missing_claims = payload.get("missing_claims") if isinstance(payload.get("missing_claims"), list) else []
        return ({
            "benchmark_id": case["benchmark_id"],
            "question_id": case["question_id"],
            "model": model,
            "expected_answer": case["answer"],
            "answer_from_evidence": answer,
            "supported": supported,
            "missing_claims": missing_claims,
            "reason": str(payload.get("reason") or ""),
            "pass": supported and answer == case["answer"] and not missing_claims,
            "usage": usage,
            "finish_reason": finish_reason,
        }, {"raw_response": raw, "usage": usage, "finish_reason": finish_reason})
    except Exception as exc:
        return failed(case, model, f"{type(exc).__name__}: {exc}")


def parse_object(raw: str) -> dict[str, Any]:
    text = raw.strip()
    fenced = re.findall(r"```(?:json)?\s*(\{.*?\})\s*```", text, flags=re.IGNORECASE | re.DOTALL)
    candidates = list(reversed(fenced)) + [text]
    for candidate in candidates:
        try:
            payload = json.loads(candidate)
        except json.JSONDecodeError:
            continue
        if isinstance(payload, dict):
            return payload
    decoder = json.JSONDecoder()
    parsed = []
    for match in re.finditer(r"\{", text):
        try:
            payload, _ = decoder.raw_decode(text[match.start():])
        except json.JSONDecodeError:
            continue
        if isinstance(payload, dict):
            parsed.append(payload)
    if not parsed:
        raise ValueError("model returned no JSON object")
    return parsed[-1]


def failed(case: dict[str, Any], model: str, error: str) -> tuple[dict[str, Any], dict[str, Any]]:
    record = {
        "benchmark_id": case["benchmark_id"],
        "question_id": case["question_id"],
        "model": model,
        "expected_answer": case["answer"],
        "answer_from_evidence": None,
        "supported": False,
        "missing_claims": [],
        "pass": False,
        "error": error,
    }
    return record, {"error": error}


def summarize(cases: list[dict[str, Any]], models: list[str], results: list[dict[str, Any]]) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    by_case: dict[str, list[dict[str, Any]]] = {}
    for result in results:
        by_case.setdefault(result["benchmark_id"], []).append(result)
    promoted = []
    details = []
    for case in cases:
        audits = by_case.get(case["benchmark_id"], [])
        passed_models = {audit["model"] for audit in audits if audit.get("pass")}
        approved = len(passed_models) == len(models)
        details.append({
            "benchmark_id": case["benchmark_id"],
            "question_id": case["question_id"],
            "approved": approved,
            "passed_models": sorted(passed_models),
            "audits": audits,
        })
        if approved:
            promoted.append({
                **case,
                "candidate_status": "evidence_audited",
                "evidence_audit_models": sorted(passed_models),
            })
    return promoted, {
        "summary": {
            "candidate_count": len(cases),
            "judge_models": models,
            "approved_count": len(promoted),
            "approved_rate": round(len(promoted) / len(cases), 4) if cases else 0.0,
            "promotion_rule": "every judge must mark evidence supported, select the labelled answer, and report no missing claims",
        },
        "cases": details,
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--golden", type=Path, required=True)
    parser.add_argument("--corpus-dir", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--api-base-url", default="https://api.tokenmp.cn/v1")
    parser.add_argument("--models", nargs="+", default=["deepseek-v4-pro", "glm-5.1"])
    parser.add_argument("--reasoning-effort", choices=["low", "medium", "high"], default="high")
    parser.add_argument("--max-output-tokens", type=int, default=4096)
    parser.add_argument("--timeout-seconds", type=float, default=300.0)
    parser.add_argument("--concurrency", type=int, default=10)
    return parser.parse_args()


if __name__ == "__main__":
    main()
