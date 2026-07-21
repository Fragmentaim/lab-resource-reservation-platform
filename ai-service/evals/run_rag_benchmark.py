"""Run retrieval/rerank and optional grounded-answer evaluation on a Golden Set.

The evaluator intentionally calls the application's ``rag_pipeline`` rather
than a copied retrieval implementation.  It compares first-stage retrieval
against the same candidates after the configured reranker, and stores each
query's returned evidence for later inspection.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import statistics
import time
from pathlib import Path
from typing import Any

from app.core import context_assembler, llm, rag_pipeline
from bootstrap_golden_from_mcq import OpenAICompatibleJudge, read_jsonl, write_json, write_jsonl


ANSWER_RE = re.compile(r'"answer"\s*:\s*"([ABCD])"|(?:答案|answer)\s*[:：]?\s*([ABCD])', re.IGNORECASE)
CHUNK_ID_RE = re.compile(r'"cited_chunk_ids"\s*:\s*\[([^]]*)\]', re.DOTALL)
QUOTED_ID_RE = re.compile(r'"([^"]+)"')


def main() -> None:
    args = parse_args()
    golden = read_jsonl(args.golden)
    document_ids = parse_document_ids(args.document_ids)
    cases = [normalize_case(item, document_ids) for item in golden]
    if not cases:
        raise SystemExit("No Golden cases found")

    output_dir = args.output_dir.resolve()
    output_dir.mkdir(parents=True, exist_ok=True)
    judge_client = build_judge_client(args)
    summary = {
        "schema_version": 1,
        "golden_path": str(args.golden.resolve()),
        "case_count": len(cases),
        "document_ids": document_ids,
        "top_ks": args.top_ks,
        "modes": {},
    }

    for mode, apply_rerank in (("first_stage", False), ("reranked", True)):
        if mode not in args.modes:
            continue
        records = run_retrieval_mode(cases, args.top_ks, apply_rerank)
        write_jsonl(output_dir / f"{mode}_retrieval.jsonl", records)
        summary["modes"][mode] = summarize_retrieval(records, args.top_ks)

        if args.answer_mode:
            answer_records = run_answer_mode(
                records,
                args.answer_top_k,
                args.model,
                judge_client,
                args.answer_max_tokens,
                args.answer_retries,
            )
            write_jsonl(output_dir / f"{mode}_answers.jsonl", answer_records)
            summary["modes"][mode]["answer"] = summarize_answers(answer_records)

    write_json(output_dir / "benchmark_report.json", summary)
    print(json.dumps(summary, ensure_ascii=False, indent=2))


def normalize_case(item: dict[str, Any], document_ids: dict[str, int]) -> dict[str, Any]:
    evidence = item.get("expected_evidence") or []
    document_keys = sorted({str(entry["document_key"]) for entry in evidence})
    missing = [key for key in document_keys if key not in document_ids]
    if missing:
        raise ValueError(f"{item['benchmark_id']} has no runtime document ID for: {', '.join(missing)}")
    return {
        **item,
        "runtime_document_ids": [document_ids[key] for key in document_keys],
        "expected_content_hashes": {str(entry["content_hash"]) for entry in evidence},
    }


def run_retrieval_mode(cases: list[dict[str, Any]], top_ks: list[int], apply_rerank: bool) -> list[dict[str, Any]]:
    max_k = max(top_ks)
    records = []
    for case in cases:
        started = time.perf_counter()
        results = rag_pipeline.retrieve_candidates(
            question=case["question"],
            document_ids=case["runtime_document_ids"],
            top_k=max_k,
            apply_rerank=apply_rerank,
        )
        latency_ms = round((time.perf_counter() - started) * 1000, 2)
        records.append({
            "benchmark_id": case["benchmark_id"],
            "question_id": case["question_id"],
            "question": case["question"],
            "options": case["options"],
            "answer": case["answer"],
            "expected_content_hashes": sorted(case["expected_content_hashes"]),
            "runtime_document_ids": case["runtime_document_ids"],
            "latency_ms": latency_ms,
            "results": [_result_snapshot(result, rank) for rank, result in enumerate(results, start=1)],
        })
    return records


def run_answer_mode(
    retrieval_records: list[dict[str, Any]],
    top_k: int,
    model: str | None,
    judge_client: OpenAICompatibleJudge | None,
    max_output_tokens: int,
    retries: int,
) -> list[dict[str, Any]]:
    records = []
    for item in retrieval_records:
        evidence = [dict(result) for result in item["results"][:top_k]]
        assembled = context_assembler.assemble_context(
            original_question=render_multiple_choice(item),
            rewritten_question="",
            ranked_results=evidence,
            session_summary=None,
            chat_history=None,
            top_k=top_k,
        )
        system = (
            assembled["system_prompt"]
            + "\n\n【评测输出强约束】你正在完成单选题评测，只能依据上述检索证据。"
            "answer 字段只能是选项字母 A、B、C、D，绝不能填写选项文字、数值或解释；"
            "证据不足时 answer 必须为 null。cited_chunk_ids 只能填写实际使用的 Chunk ID。"
            "只输出 JSON，不要输出其他文字："
            '{"answer":"A","cited_chunk_ids":["chunk-id"],"grounded":true}'
        )
        started = time.perf_counter()
        try:
            raw_answer, usage = answer_once(
                judge_client,
                model,
                system,
                assembled,
                max_output_tokens,
                retries,
            )
        except Exception as exc:
            records.append({
                "benchmark_id": item["benchmark_id"],
                "question_id": item["question_id"],
                "expected_answer": item["answer"],
                "answer": None,
                "correct": False,
                "cited_chunk_ids": [],
                "citation_hit": False,
                "grounded_correct": False,
                "latency_ms": round((time.perf_counter() - started) * 1000, 2),
                "context_stats": assembled["context_stats"],
                "provider_usage": {},
                "error": f"{type(exc).__name__}: {exc}",
            })
            continue
        latency_ms = round((time.perf_counter() - started) * 1000, 2)
        answer, cited_ids = parse_answer(raw_answer)
        returned_hashes = {
            str(source.get("content_hash"))
            for source in evidence
            if source.get("chunk_id") in cited_ids and source.get("content_hash")
        }
        expected_hashes = set(item["expected_content_hashes"])
        records.append({
            "benchmark_id": item["benchmark_id"],
            "question_id": item["question_id"],
            "expected_answer": item["answer"],
            "answer": answer,
            "correct": answer == item["answer"],
            "cited_chunk_ids": cited_ids,
            "citation_hit": bool(expected_hashes.intersection(returned_hashes)),
            "grounded_correct": answer == item["answer"] and bool(expected_hashes.intersection(returned_hashes)),
            "latency_ms": latency_ms,
            "context_stats": assembled["context_stats"],
            "provider_usage": usage,
            "raw_answer": raw_answer,
        })
    return records


def answer_once(
    judge_client: OpenAICompatibleJudge | None,
    model: str | None,
    system: str,
    assembled: dict[str, Any],
    max_output_tokens: int,
    retries: int,
) -> tuple[str, dict[str, Any]]:
    last_error = None
    for attempt in range(max(0, retries) + 1):
        try:
            if judge_client is not None:
                if not model:
                    raise ValueError("--model is required when --judge-api-base-url is set")
                raw, usage, _ = judge_client.ask(model, system, assembled["user_prompt"], max_output_tokens)
                return raw, usage
            return llm.chat_with_usage(system, assembled["user_prompt"], assembled["history"], model)
        except Exception as exc:
            last_error = exc
            if attempt < retries:
                time.sleep(min(4, 1 + attempt))
    raise last_error  # type: ignore[misc]


def _result_snapshot(result: dict[str, Any], rank: int) -> dict[str, Any]:
    return {
        "rank": rank,
        "chunk_id": result.get("chunk_id"),
        "content_hash": result.get("content_hash"),
        "document_id": result.get("document_id"),
        "page_no": result.get("page_no"),
        "score": result.get("score"),
        "retrieval_score": result.get("retrieval_score"),
        "rerank_score": result.get("rerank_score"),
        "rerank_provider": result.get("rerank_provider"),
        "retrieval_source": result.get("retrieval_source"),
        "content": result.get("content"),
    }


def summarize_retrieval(records: list[dict[str, Any]], top_ks: list[int]) -> dict[str, Any]:
    summary: dict[str, Any] = {
        "case_count": len(records),
        "latency_ms": percentile_summary([item["latency_ms"] for item in records]),
    }
    for top_k in top_ks:
        recalls = []
        hit_rates = []
        reciprocal_ranks = []
        for item in records:
            expected = set(item["expected_content_hashes"])
            returned = [str(row.get("content_hash") or "") for row in item["results"][:top_k]]
            hits = expected.intersection(returned)
            recalls.append(len(hits) / len(expected) if expected else 1.0)
            hit_rates.append(1.0 if hits else 0.0)
            first = next((rank for rank, content_hash in enumerate(returned, start=1) if content_hash in expected), None)
            reciprocal_ranks.append(0.0 if first is None else 1.0 / first)
        summary[f"recall_at_{top_k}"] = round(sum(recalls) / len(recalls), 4)
        summary[f"hit_rate_at_{top_k}"] = round(sum(hit_rates) / len(hit_rates), 4)
        summary[f"mrr_at_{top_k}"] = round(sum(reciprocal_ranks) / len(reciprocal_ranks), 4)
    return summary


def summarize_answers(records: list[dict[str, Any]]) -> dict[str, Any]:
    total = len(records)
    return {
        "case_count": total,
        "answer_accuracy": rate(records, "correct"),
        "citation_hit_rate": rate(records, "citation_hit"),
        "grounded_accuracy": rate(records, "grounded_correct"),
        "latency_ms": percentile_summary([item["latency_ms"] for item in records]),
        "average_context_tokens": round(sum(item["context_stats"].get("evidence_tokens", 0) for item in records) / total, 2) if total else 0,
    }


def parse_answer(raw: str) -> tuple[str | None, list[str]]:
    match = ANSWER_RE.search(raw or "")
    answer = next((value for value in match.groups() if value), None) if match else None
    chunk_match = CHUNK_ID_RE.search(raw or "")
    cited = QUOTED_ID_RE.findall(chunk_match.group(1)) if chunk_match else []
    return answer.upper() if answer else None, list(dict.fromkeys(cited))


def parse_document_ids(items: list[str]) -> dict[str, int]:
    parsed = {}
    for item in items:
        key, separator, raw_id = item.partition("=")
        if not separator or not key.strip() or not raw_id.strip().isdigit():
            raise ValueError(f"Invalid --document-id value: {item}; expected D1=9001")
        parsed[key.strip()] = int(raw_id.strip())
    return parsed


def build_judge_client(args: argparse.Namespace) -> OpenAICompatibleJudge | None:
    if not args.judge_api_base_url:
        return None
    api_key = os.environ.get(args.judge_api_key_env, "").strip()
    if not api_key:
        raise ValueError(f"{args.judge_api_key_env} is required when --judge-api-base-url is set")
    return OpenAICompatibleJudge(
        args.judge_api_base_url,
        api_key,
        args.judge_timeout_seconds,
        args.reasoning_effort,
    )


def percentile_summary(values: list[float]) -> dict[str, float]:
    if not values:
        return {"p50": 0.0, "p95": 0.0, "mean": 0.0}
    ordered = sorted(values)
    return {
        "p50": round(statistics.median(ordered), 2),
        "p95": round(ordered[min(len(ordered) - 1, max(0, int(len(ordered) * 0.95) - 1))], 2),
        "mean": round(statistics.fmean(ordered), 2),
    }


def rate(records: list[dict[str, Any]], field: str) -> float:
    return round(sum(1 for item in records if item.get(field)) / len(records), 4) if records else 0.0


def render_multiple_choice(item: dict[str, Any]) -> str:
    options = "\n".join(f"{key}. {value}" for key, value in item.get("options", {}).items())
    return f"{item['question']}\n{options}"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--golden", type=Path, required=True)
    parser.add_argument("--document-id", dest="document_ids", action="append", default=[], required=True,
                        help="Runtime document mapping, e.g. D1=9001. Repeat for every source document.")
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--top-ks", type=int, nargs="+", default=[1, 3, 5])
    parser.add_argument("--modes", nargs="+", choices=["first_stage", "reranked"], default=["first_stage", "reranked"])
    parser.add_argument("--answer-mode", action="store_true")
    parser.add_argument("--answer-top-k", type=int, default=3)
    parser.add_argument("--model", default=None)
    parser.add_argument("--answer-max-tokens", type=int, default=4096)
    parser.add_argument("--answer-retries", type=int, default=2)
    parser.add_argument("--judge-api-base-url", default="", help="Optional OpenAI-compatible evaluator endpoint.")
    parser.add_argument("--judge-api-key-env", default="TOKENMP_API_KEY")
    parser.add_argument("--judge-timeout-seconds", type=float, default=300.0)
    parser.add_argument("--reasoning-effort", choices=["low", "medium", "high"], default="high")
    return parser.parse_args()


if __name__ == "__main__":
    main()
