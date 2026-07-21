"""Diagnose whether minimal evidence caused a model's wrong answer.

For every question that failed in the minimal-context phase, this evaluator
asks each judge model one question at a time with the complete parsed corpus.
The request uses the same high reasoning setting as the final benchmark.
"""

from __future__ import annotations

import argparse
import json
import os
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import asdict
from pathlib import Path

from bootstrap_golden_from_mcq import (
    DEFAULT_MODELS,
    OpenAICompatibleJudge,
    ask_full_context_batch,
    build_chunks,
    read_jsonl,
    render_context,
    safe_file_name,
    select_questions,
    write_json,
    write_jsonl,
)


def main() -> None:
    args = parse_args()
    api_key = os.environ.get("TOKENMP_API_KEY", "").strip()
    if not api_key:
        raise SystemExit("TOKENMP_API_KEY is required")

    output_dir = args.output_dir.resolve()
    output_dir.mkdir(parents=True, exist_ok=True)
    questions = select_questions(args.question_bank, args.sample_size, args.seed)
    phase2 = read_jsonl(args.phase2_results)
    latest_phase2 = {(item["question_id"], item["model"]): item for item in phase2}
    bad_ids = sorted(
        {
            item["question_id"]
            for item in latest_phase2.values()
            if not item.get("correct") or not item.get("format_valid") or item.get("error")
        },
        key=int,
    )
    question_by_id = {question.question_id: question for question in questions}
    target_questions = [question_by_id[question_id] for question_id in bad_ids if question_id in question_by_id]
    chunks = build_chunks(args.corpus_dir)
    full_context = render_context(chunks, args.full_context_char_cap)
    valid_ids = {chunk["chunk_id"] for chunk in chunks}
    client = OpenAICompatibleJudge(
        args.api_base_url,
        api_key,
        args.timeout_seconds,
        reasoning_effort=args.reasoning_effort,
    )

    tasks = [(model, question) for model in args.models for question in target_questions]
    results: list[dict] = []
    raw_responses: list[dict] = []
    with ThreadPoolExecutor(max_workers=args.concurrency) as executor:
        futures = {
            executor.submit(
                ask_full_context_batch,
                client,
                model,
                [question],
                full_context,
                valid_ids,
                args.max_output_tokens,
            ): (model, question)
            for model, question in tasks
        }
        for future in as_completed(futures):
            model, question = futures[future]
            records, raw = future.result()
            record = records[0]
            minimal = latest_phase2.get((question.question_id, model), {})
            record.update({
                "diagnostic_stage": "full_context_single_question",
                "minimal_answer": minimal.get("answer"),
                "minimal_correct": minimal.get("correct"),
                "minimal_format_valid": minimal.get("format_valid"),
                "minimal_evidence_chunk_ids": minimal.get("evidence_chunk_ids", []),
                "full_context_char_count": len(full_context),
            })
            results.append(record)
            raw_responses.append({"model": model, "question_id": question.question_id, **raw})
            print(
                f"diagnostic question={question.question_id} model={model} "
                f"full_correct={record.get('correct')} minimal_correct={minimal.get('correct')}",
                flush=True,
            )

    write_jsonl(output_dir / "diagnostic_results.jsonl", results)
    write_jsonl(output_dir / "diagnostic_raw_responses.jsonl", raw_responses)
    write_jsonl(output_dir / "questions.jsonl", [asdict(question) for question in target_questions])
    write_json(output_dir / "diagnostic_manifest.json", {
        "sample_size": args.sample_size,
        "seed": args.seed,
        "target_question_count": len(target_questions),
        "target_question_ids": bad_ids,
        "model_count": len(args.models),
        "request_count": len(results),
        "full_context_character_count": len(full_context),
        "reasoning_effort": args.reasoning_effort,
        "concurrency": args.concurrency,
    })

    report = summarize(target_questions, args.models, results)
    write_json(output_dir / "diagnostic_report.json", report)
    print(json.dumps(report["summary"], ensure_ascii=False, indent=2))


def summarize(questions, models: list[str], results: list[dict]) -> dict:
    by_question = {}
    for result in results:
        by_question.setdefault(result["question_id"], {})[result["model"]] = result
    rows = []
    for question in questions:
        model_rows = []
        for model in models:
            result = by_question.get(question.question_id, {}).get(model, {})
            model_rows.append({
                "model": model,
                "minimal_correct": result.get("minimal_correct"),
                "full_context_correct": result.get("correct"),
                "minimal_format_valid": result.get("minimal_format_valid"),
                "full_context_format_valid": result.get("format_valid"),
                "full_context_evidence_chunk_ids": result.get("evidence_chunk_ids", []),
            })
        recovered = sum(
            1 for item in model_rows
            if item["minimal_correct"] is False and item["full_context_correct"] is True
        )
        rows.append({
            "question_id": question.question_id,
            "expected_answer": question.answer,
            "full_context_recovered_model_count": recovered,
            "models": model_rows,
        })
    total = len(results)
    return {
        "summary": {
            "question_count": len(questions),
            "request_count": total,
            "full_context_accuracy": round(sum(1 for item in results if item.get("correct")) / total, 4) if total else 0,
            "minimal_to_full_recoveries": sum(item["full_context_recovered_model_count"] for item in rows),
            "questions_with_recovery": sum(1 for item in rows if item["full_context_recovered_model_count"] > 0),
        },
        "questions": rows,
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--question-bank", type=Path, required=True)
    parser.add_argument("--corpus-dir", type=Path, required=True)
    parser.add_argument("--phase2-results", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--api-base-url", default="https://api.tokenmp.cn/v1")
    parser.add_argument("--models", nargs="+", default=DEFAULT_MODELS)
    parser.add_argument("--sample-size", type=int, default=50)
    parser.add_argument("--seed", type=int, default=20250720)
    parser.add_argument("--full-context-char-cap", type=int, default=320_000)
    parser.add_argument("--timeout-seconds", type=float, default=600.0)
    parser.add_argument("--max-output-tokens", type=int, default=16_384)
    parser.add_argument("--reasoning-effort", choices=["low", "medium", "high"], default="high")
    parser.add_argument("--concurrency", type=int, default=10)
    return parser.parse_args()


if __name__ == "__main__":
    main()
