"""Evaluate adaptive evidence selection for questions hurt by narrow evidence.

The candidate pool is built from the existing full-context diagnostic evidence,
the original minimal evidence, and neighboring chunks from the same document.
Each judge must select the smallest sufficient evidence set from that pool and
answer using only the selected set.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path
from typing import Any

from bootstrap_golden_from_mcq import (
    DEFAULT_MODELS,
    OpenAICompatibleJudge,
    build_chunks,
    read_jsonl,
    render_context,
    render_questions,
    select_questions,
    write_json,
    write_jsonl,
)


CHUNK_ID_RE = re.compile(r"^D(?P<document>\d+)-C(?P<chunk>\d+)$")


def main() -> None:
    args = parse_args()
    api_key = os.environ.get("TOKENMP_API_KEY", "").strip()
    if not api_key:
        raise SystemExit("TOKENMP_API_KEY is required")

    output_dir = args.output_dir.resolve()
    output_dir.mkdir(parents=True, exist_ok=True)
    questions = select_questions(args.question_bank, args.sample_size, args.seed)
    question_by_id = {question.question_id: question for question in questions}
    phase2 = read_jsonl(args.phase2_results)
    phase2_by_key = {(item["question_id"], item["model"]): item for item in phase2}
    bad_ids = sorted(
        {
            item["question_id"]
            for item in phase2_by_key.values()
            if not item.get("correct") or not item.get("format_valid") or item.get("error")
        },
        key=int,
    )
    target_questions = [question_by_id[item] for item in bad_ids if item in question_by_id]

    chunks = build_chunks(args.corpus_dir)
    chunks_by_id = {chunk["chunk_id"]: chunk for chunk in chunks}
    neighboring = build_neighbor_map(chunks, args.neighbor_radius)
    diagnostic = read_jsonl(args.diagnostic_results)
    diagnostic_by_key = {(item["question_id"], item["model"]): item for item in diagnostic}

    candidate_pools = {}
    for question in target_questions:
        base_ids: set[str] = set()
        for model in args.models:
            full = diagnostic_by_key.get((question.question_id, model), {})
            minimal = phase2_by_key.get((question.question_id, model), {})
            base_ids.update(full.get("full_context_evidence_chunk_ids") or full.get("evidence_chunk_ids") or [])
            base_ids.update(minimal.get("evidence_chunk_ids") or [])
        pool = set(base_ids)
        for chunk_id in list(base_ids):
            pool.update(neighboring.get(chunk_id, []))
        candidate_pools[question.question_id] = sorted(
            (item for item in pool if item in chunks_by_id), key=chunk_sort_key
        )

    client = OpenAICompatibleJudge(
        args.api_base_url,
        api_key,
        args.timeout_seconds,
        reasoning_effort=args.reasoning_effort,
    )
    tasks = [(model, question) for model in args.models for question in target_questions]
    results: list[dict[str, Any]] = []
    raw_responses: list[dict[str, Any]] = []
    with ThreadPoolExecutor(max_workers=args.concurrency) as executor:
        futures = {
            executor.submit(
                ask_adaptive,
                client,
                model,
                question,
                candidate_pools[question.question_id],
                chunks_by_id,
                args.max_output_tokens,
            ): (model, question)
            for model, question in tasks
        }
        for future in as_completed(futures):
            model, question = futures[future]
            record, raw = future.result()
            original = phase2_by_key.get((question.question_id, model), {})
            record.update({
                "minimal_answer": original.get("answer"),
                "minimal_correct": original.get("correct"),
                "minimal_evidence_chunk_ids": original.get("evidence_chunk_ids", []),
                "candidate_pool_chunk_ids": candidate_pools[question.question_id],
            })
            results.append(record)
            raw_responses.append({"model": model, "question_id": question.question_id, **raw})
            print(
                f"adaptive question={question.question_id} model={model} "
                f"correct={record.get('correct')} evidence={len(record.get('evidence_chunk_ids', []))}",
                flush=True,
            )

    write_jsonl(output_dir / "adaptive_results.jsonl", results)
    write_jsonl(output_dir / "adaptive_raw_responses.jsonl", raw_responses)
    write_jsonl(output_dir / "questions.jsonl", [question.__dict__ for question in target_questions])
    report = summarize(target_questions, args.models, results)
    write_json(output_dir / "adaptive_report.json", report)
    write_json(output_dir / "adaptive_manifest.json", {
        "target_question_count": len(target_questions),
        "target_question_ids": bad_ids,
        "model_count": len(args.models),
        "request_count": len(results),
        "reasoning_effort": args.reasoning_effort,
        "concurrency": args.concurrency,
        "neighbor_radius": args.neighbor_radius,
    })
    print(json.dumps(report["summary"], ensure_ascii=False, indent=2))


def ask_adaptive(
    client: OpenAICompatibleJudge,
    model: str,
    question: Any,
    candidate_ids: list[str],
    chunks_by_id: dict[str, dict[str, Any]],
    max_output_tokens: int,
) -> tuple[dict[str, Any], dict[str, Any]]:
    system = (
        "你是严格的 RoboMaster 规则测评裁判。只能根据候选资料答题，不得使用外部知识。"
        "先识别题目和四个选项分别需要验证的事实，再从候选 Chunk 中选择覆盖全部必要事实的最少证据。"
        "证据不足时不要猜测，返回 sufficient=false。最多选择 6 个 Chunk。"
        "只输出一个 JSON 对象："
        '{"answer":"A","evidence_chunk_ids":["D1-C0001"],'
        '"covered_claims":["..."],"missing_claims":[],"sufficient":true,"confidence":0.0}'
    )
    evidence = render_context([chunks_by_id[item] for item in candidate_ids])
    user = f"题目：\n{render_questions([question])}\n\n候选资料：\n{evidence}"
    started = time.monotonic()
    try:
        raw, usage, finish_reason = client.ask(model, system, user, max_output_tokens)
        payload = parse_adaptive_object(raw)
        answer = str(payload.get("answer", "")).strip().upper()
        answer = answer if answer in {"A", "B", "C", "D"} else None
        selected = payload.get("evidence_chunk_ids") or []
        if not isinstance(selected, list):
            selected = []
        selected = list(dict.fromkeys(str(item) for item in selected if str(item) in candidate_ids))[:6]
        covered = payload.get("covered_claims") if isinstance(payload.get("covered_claims"), list) else []
        missing = payload.get("missing_claims") if isinstance(payload.get("missing_claims"), list) else []
        sufficient = bool(payload.get("sufficient"))
        return ({
            "stage": "adaptive_evidence",
            "question_id": question.question_id,
            "model": model,
            "expected_answer": question.answer,
            "answer": answer,
            "correct": answer == question.answer,
            "format_valid": answer is not None and bool(selected),
            "evidence_chunk_ids": selected,
            "evidence_count": len(selected),
            "covered_claims": covered,
            "missing_claims": missing,
            "sufficient": sufficient,
            "usage": usage,
            "finish_reason": finish_reason,
            "latency_seconds": round(time.monotonic() - started, 3),
        }, {"raw_response": raw, "usage": usage, "finish_reason": finish_reason})
    except Exception as exc:
        return ({
            "stage": "adaptive_evidence",
            "question_id": question.question_id,
            "model": model,
            "expected_answer": question.answer,
            "answer": None,
            "correct": False,
            "format_valid": False,
            "evidence_chunk_ids": [],
            "evidence_count": 0,
            "covered_claims": [],
            "missing_claims": [],
            "sufficient": False,
            "error": f"{type(exc).__name__}: {exc}",
            "latency_seconds": round(time.monotonic() - started, 3),
        }, {"error": f"{type(exc).__name__}: {exc}"})


def parse_adaptive_object(raw: str) -> dict[str, Any]:
    """Parse the single-object schema used by the adaptive selector."""
    text = raw.strip()
    fenced = re.findall(r"```(?:json)?\s*(\{.*?\})\s*```", text, flags=re.IGNORECASE | re.DOTALL)
    for candidate in reversed(fenced):
        try:
            payload = json.loads(candidate)
        except json.JSONDecodeError:
            continue
        if isinstance(payload, dict) and "answer" in payload:
            return payload
    decoder = json.JSONDecoder()
    candidates: list[dict[str, Any]] = []
    for match in re.finditer(r"\{", text):
        try:
            payload, _ = decoder.raw_decode(text[match.start():])
        except json.JSONDecodeError:
            continue
        if isinstance(payload, dict) and "answer" in payload:
            candidates.append(payload)
    if not candidates:
        raise ValueError("model returned no valid adaptive evidence JSON object")
    return candidates[-1]


def build_neighbor_map(chunks: list[dict[str, Any]], radius: int = 1) -> dict[str, list[str]]:
    radius = max(0, radius)
    by_document: dict[int, list[dict[str, Any]]] = {}
    for chunk in chunks:
        by_document.setdefault(int(chunk["document_index"]), []).append(chunk)
    neighbors: dict[str, list[str]] = {}
    for items in by_document.values():
        items.sort(key=lambda item: int(item["chunk_id"].split("-C")[1]))
        for index, chunk in enumerate(items):
            neighbors[chunk["chunk_id"]] = [
                item["chunk_id"]
                for item in items[max(0, index - radius):min(len(items), index + radius + 1)]
                if item["chunk_id"] != chunk["chunk_id"]
            ]
    return neighbors


def chunk_sort_key(chunk_id: str) -> tuple[int, int]:
    match = CHUNK_ID_RE.match(chunk_id)
    return (int(match.group("document")), int(match.group("chunk"))) if match else (999, 999999)


def summarize(questions: list[Any], models: list[str], results: list[dict[str, Any]]) -> dict[str, Any]:
    total = len(results)
    correct = sum(1 for item in results if item.get("correct"))
    recoveries = sum(1 for item in results if item.get("minimal_correct") is False and item.get("correct"))
    by_model = {}
    for model in models:
        rows = [item for item in results if item["model"] == model]
        by_model[model] = {
            "accuracy": round(sum(1 for item in rows if item.get("correct")) / len(rows), 4) if rows else 0,
            "format_valid_rate": round(sum(1 for item in rows if item.get("format_valid")) / len(rows), 4) if rows else 0,
            "average_evidence_count": round(sum(item.get("evidence_count", 0) for item in rows) / len(rows), 2) if rows else 0,
        }
    return {
        "summary": {
            "question_count": len(questions),
            "request_count": total,
            "accuracy": round(correct / total, 4) if total else 0,
            "minimal_to_adaptive_recoveries": recoveries,
            "average_evidence_count": round(sum(item.get("evidence_count", 0) for item in results) / total, 2) if total else 0,
            "adaptive_sufficient_rate": round(sum(1 for item in results if item.get("sufficient")) / total, 4) if total else 0,
        },
        "model_metrics": by_model,
        "question_metrics": [
            {
                "question_id": question.question_id,
                "results": [item for item in results if item["question_id"] == question.question_id],
            }
            for question in questions
        ],
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--question-bank", type=Path, required=True)
    parser.add_argument("--corpus-dir", type=Path, required=True)
    parser.add_argument("--phase2-results", type=Path, required=True)
    parser.add_argument("--diagnostic-results", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--api-base-url", default="https://api.tokenmp.cn/v1")
    parser.add_argument("--models", nargs="+", default=DEFAULT_MODELS)
    parser.add_argument("--sample-size", type=int, default=50)
    parser.add_argument("--seed", type=int, default=20250720)
    parser.add_argument("--timeout-seconds", type=float, default=600.0)
    parser.add_argument("--max-output-tokens", type=int, default=16384)
    parser.add_argument("--reasoning-effort", choices=["low", "medium", "high"], default="high")
    parser.add_argument("--concurrency", type=int, default=10)
    parser.add_argument("--neighbor-radius", type=int, default=1)
    return parser.parse_args()


if __name__ == "__main__":
    main()
