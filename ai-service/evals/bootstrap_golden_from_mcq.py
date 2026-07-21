"""Bootstrap candidate Golden evidence from a multiple-choice question bank.

This is deliberately an *offline evaluation tool*, not a runtime RAG shortcut.
It first gives each judge model the complete parsed corpus, asks it to answer a
question and select the smallest sufficient evidence set, then validates the
selected evidence alone with every judge model.  Only samples that pass both
stages can become candidate Golden samples for retrieval evaluation.

The API credential is read only from TOKENMP_API_KEY.  It is never persisted in
the output artifacts, prompts, command line, or repository.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import random
import re
import time
from collections import Counter
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any

import httpx
from openpyxl import load_workbook

from app.core.chunker import chunk_document
from app.core.parser import parse_file


DEFAULT_MODELS = ["deepseek-v4-pro", "glm-5.1", "MiniMax-M3", "kimi-k2.6"]
ANSWER_RE = re.compile(r'"answer"\s*:\s*"?([ABCD])"?', re.IGNORECASE)
EVIDENCE_RE = re.compile(r'"evidence_chunk_ids"\s*:\s*\[([^]]*)\]', re.DOTALL)
QUOTED_VALUE_RE = re.compile(r'"([^"\n]+)"')


@dataclass(frozen=True)
class Question:
    question_id: str
    stem: str
    options: dict[str, str]
    answer: str

    def prompt_text(self) -> str:
        options = "\n".join(f"{key}. {value}" for key, value in self.options.items())
        return f"题号：{self.question_id}\n题目：{self.stem}\n{options}"


def main() -> None:
    args = parse_args()
    api_key = os.environ.get("TOKENMP_API_KEY", "").strip()
    if not api_key:
        raise SystemExit("TOKENMP_API_KEY is required and must be provided through the environment")

    output_dir = args.output_dir.resolve()
    output_dir.mkdir(parents=True, exist_ok=True)
    chunks = build_chunks(args.corpus_dir)
    questions = select_questions(args.question_bank, args.sample_size, args.seed)
    if args.limit_questions:
        questions = questions[:args.limit_questions]

    write_json(output_dir / "run_manifest.json", {
        "schema_version": 1,
        "models": args.models,
        "question_count": len(questions),
        "seed": args.seed,
        "chunk_count": len(chunks),
        "context_character_count": sum(len(chunk["content"]) for chunk in chunks),
        "corpus_fingerprint": fingerprint(chunks),
        "question_fingerprint": fingerprint([asdict(question) for question in questions]),
        "stage": args.stage,
    })
    write_jsonl(output_dir / "chunks.jsonl", chunks)
    write_jsonl(output_dir / "questions.jsonl", [asdict(question) for question in questions])

    client = OpenAICompatibleJudge(args.api_base_url, api_key, args.timeout_seconds)
    phase1_path = output_dir / "phase1_full_context.jsonl"
    phase2_path = output_dir / "phase2_minimal_context.jsonl"
    response_dir = output_dir / "model_responses"
    response_dir.mkdir(exist_ok=True)

    if args.phase1_input_dirs:
        imported = []
        for source_dir in args.phase1_input_dirs:
            source_path = source_dir / "phase1_full_context.jsonl"
            if not source_path.exists():
                raise FileNotFoundError(f"Missing phase1 result file: {source_path}")
            imported.extend(read_jsonl(source_path))
        deduplicated = {(item["question_id"], item["model"]): item for item in imported}
        write_jsonl(phase1_path, list(deduplicated.values()))

    if args.stage in {"all", "answer"}:
        complete_phase1 = load_existing(phase1_path)
        full_context = render_context(chunks, args.full_context_char_cap)
        for model in args.models:
            pending = [question for question in questions if (question.question_id, model) not in complete_phase1]
            for batch_index, batch in enumerate(batches(pending, args.batch_size), start=1):
                results, raw = ask_full_context_batch(
                    client, model, batch, full_context, set(chunk["chunk_id"] for chunk in chunks), args.max_output_tokens
                )
                write_json(response_dir / f"phase1_{safe_file_name(model)}_{batch_index:02d}.json", raw)
                for result in results:
                    append_jsonl(phase1_path, result)
                    print_progress("phase1", result["question_id"], model, result)
        if args.stage == "answer":
            return

    if args.stage in {"all", "validate"}:
        phase1 = read_jsonl(phase1_path)
        candidates = choose_minimal_candidates(questions, phase1)
        write_jsonl(output_dir / "candidate_evidence.jsonl", candidates)
        by_id = {chunk["chunk_id"]: chunk for chunk in chunks}
        complete_phase2 = load_existing(phase2_path)
        candidate_by_id = {candidate["question_id"]: candidate for candidate in candidates}
        eligible_questions = [question for question in questions if candidate_by_id[question.question_id]["evidence_chunk_ids"]]
        for model in args.models:
            pending = [question for question in eligible_questions if (question.question_id, model) not in complete_phase2]
            for batch_index, batch in enumerate(batches(pending, args.batch_size), start=1):
                results, raw = ask_minimal_context_batch(
                    client, model, batch, candidate_by_id, by_id, args.max_output_tokens
                )
                write_json(response_dir / f"phase2_{safe_file_name(model)}_{batch_index:02d}.json", raw)
                for result in results:
                    append_jsonl(phase2_path, result)
                    print_progress("phase2", result["question_id"], model, result)

        report = summarize(questions, args.models, phase1, read_jsonl(phase2_path), candidates)
        write_json(output_dir / "golden_bootstrap_report.json", report)
        write_jsonl(output_dir / "candidate_golden.jsonl", report["candidate_golden"])
        print(json.dumps(report["summary"], ensure_ascii=False, indent=2))


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--question-bank", type=Path, required=True)
    parser.add_argument("--corpus-dir", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--api-base-url", default="https://api.tokenmp.cn/v1")
    parser.add_argument("--models", nargs="+", default=DEFAULT_MODELS)
    parser.add_argument("--sample-size", type=int, default=50)
    parser.add_argument("--seed", type=int, default=20250720)
    parser.add_argument("--full-context-char-cap", type=int, default=320_000)
    parser.add_argument("--timeout-seconds", type=float, default=180.0)
    parser.add_argument("--max-output-tokens", type=int, default=16_384)
    parser.add_argument("--batch-size", type=int, default=10)
    parser.add_argument("--phase1-input-dirs", type=Path, nargs="*", default=[],
                        help="Import independently generated phase-1 result directories before validation.")
    parser.add_argument("--limit-questions", type=int, default=0,
                        help="Run a deterministic pilot against the first N sampled questions.")
    parser.add_argument("--stage", choices=["all", "answer", "validate"], default="all")
    return parser.parse_args()


def build_chunks(corpus_dir: Path) -> list[dict[str, Any]]:
    chunks: list[dict[str, Any]] = []
    for document_index, pdf_path in enumerate(sorted(corpus_dir.glob("*.pdf")), start=1):
        parsed = parse_file(str(pdf_path), "PDF")
        for chunk in chunk_document(parsed):
            chunks.append({
                "chunk_id": f"D{document_index}-C{chunk['chunk_index']:04d}",
                "document_name": pdf_path.name,
                "document_index": document_index,
                "page_no": chunk.get("page_no"),
                "section_title": chunk.get("section_title"),
                "title_path": chunk.get("title_path") or [],
                "block_type": chunk.get("block_type"),
                "chunk_strategy": chunk.get("chunk_strategy"),
                "content": chunk["content"],
                "content_hash": chunk["content_hash"],
            })
    if not chunks:
        raise ValueError(f"No PDF chunks were generated from {corpus_dir}")
    return chunks


def select_questions(path: Path, sample_size: int, seed: int) -> list[Question]:
    workbook = load_workbook(path, read_only=True, data_only=True)
    try:
        sheet = workbook.active
        rows = list(sheet.iter_rows(values_only=True))
    finally:
        workbook.close()
    if not rows:
        raise ValueError("Question bank is empty")
    header = [str(value).strip() if value is not None else "" for value in rows[0]]
    required = ["题号", "题面", "选项1", "选项2", "选项3", "选项4", "答案"]
    if header[:len(required)] != required:
        raise ValueError(f"Unexpected question bank columns: {header}")

    questions = []
    for row in rows[1:]:
        if len(row) < 7 or row[0] is None:
            continue
        answer = str(row[6]).strip().upper()
        if answer not in {"A", "B", "C", "D"}:
            continue
        questions.append(Question(
            question_id=str(row[0]).strip(),
            stem=str(row[1]).strip(),
            options={letter: str(row[index]).strip() for letter, index in zip("ABCD", range(2, 6))},
            answer=answer,
        ))
    if len(questions) < sample_size:
        raise ValueError(f"Only {len(questions)} valid questions, cannot sample {sample_size}")
    return sorted(random.Random(seed).sample(questions, sample_size), key=lambda item: int(item.question_id))


class OpenAICompatibleJudge:
    def __init__(self, api_base_url: str, api_key: str, timeout_seconds: float) -> None:
        self._url = f"{api_base_url.rstrip('/')}/chat/completions"
        self._headers = {"Authorization": f"Bearer {api_key}", "Content-Type": "application/json"}
        self._timeout = timeout_seconds

    def ask(self, model: str, system: str, user: str, max_tokens: int) -> tuple[str, dict[str, Any], str | None]:
        response = httpx.post(
            self._url,
            headers=self._headers,
            json={
                "model": model,
                "temperature": 0,
                # Reasoning-capable OpenAI-compatible models may spend part of
                # this budget on hidden reasoning before producing message
                # content.  A small output cap therefore yields a valid HTTP
                # response with no final answer at all.
                "max_tokens": max_tokens,
                "messages": [{"role": "system", "content": system}, {"role": "user", "content": user}],
            },
            timeout=self._timeout,
        )
        response.raise_for_status()
        payload = response.json()
        choice = payload["choices"][0]
        message = choice.get("message") or {}
        content = message.get("content")
        if not content:
            raise ValueError(f"model returned no final content (finish_reason={choice.get('finish_reason')})")
        return str(content), payload.get("usage") or {}, choice.get("finish_reason")


def ask_full_context_batch(
    client: OpenAICompatibleJudge,
    model: str,
    questions: list[Question],
    full_context: str,
    valid_ids: set[str],
    max_output_tokens: int,
) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    system = (
        "你是严格的 RoboMaster 规则测评裁判。只能根据给定资料答题，不得使用外部知识。"
        "对每一题选出 A/B/C/D，并从资料中选择回答该题不可或缺的最少证据块，最多 3 个。"
        "必须只输出一个 JSON 对象，格式为："
        "{\"answers\":[{\"question_id\":\"12\",\"answer\":\"A\","
        "\"evidence_chunk_ids\":[\"D1-C0001\"],\"confidence\":0.0}]}。"
    )
    user = f"待答题目：\n{render_questions(questions)}\n\n完整资料如下：\n{full_context}"
    started = time.monotonic()
    try:
        raw, usage, finish_reason = client.ask(model, system, user, max_output_tokens)
        parsed = parse_batch_answer(raw, questions, valid_ids, require_evidence=True)
        records = [
            result_record("phase1", question, model, parsed[question.question_id], usage, started, finish_reason)
            for question in questions
        ]
        return records, {"model": model, "finish_reason": finish_reason, "usage": usage, "raw_response": raw}
    except Exception as exc:
        return ([failed_result("phase1", question, model, exc, started) for question in questions],
                {"model": model, "error": f"{type(exc).__name__}: {exc}"})


def ask_minimal_context_batch(
    client: OpenAICompatibleJudge,
    model: str,
    questions: list[Question],
    candidates: dict[str, dict[str, Any]],
    chunks_by_id: dict[str, dict[str, Any]],
    max_output_tokens: int,
) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    system = (
        "你是严格的 RoboMaster 规则测评裁判。只能根据每题紧随其后的最小证据答题，不得使用外部知识。"
        "必须只输出一个 JSON 对象，格式为："
        "{\"answers\":[{\"question_id\":\"12\",\"answer\":\"A\",\"confidence\":0.0}]}。"
    )
    grouped_context = render_grouped_minimal_context(questions, candidates, chunks_by_id)
    user = (
        "每一题都必须仅使用该题编号下的证据：\n"
        f"{render_questions(questions)}\n\n按题编号分组的最小证据：\n{grouped_context}"
    )
    started = time.monotonic()
    try:
        raw, usage, finish_reason = client.ask(model, system, user, max_output_tokens)
        parsed = parse_batch_answer(raw, questions, set(), require_evidence=False)
        records = []
        for question in questions:
            record = result_record("phase2", question, model, parsed[question.question_id], usage, started, finish_reason)
            record["evidence_chunk_ids"] = candidates[question.question_id]["evidence_chunk_ids"]
            record["selected_from_model"] = candidates[question.question_id].get("selected_from_model")
            records.append(record)
        return records, {"model": model, "finish_reason": finish_reason, "usage": usage, "raw_response": raw}
    except Exception as exc:
        return ([failed_result("phase2", question, model, exc, started) for question in questions],
                {"model": model, "error": f"{type(exc).__name__}: {exc}"})


def parse_batch_answer(
    raw: str,
    questions: list[Question],
    valid_ids: set[str],
    require_evidence: bool,
) -> dict[str, dict[str, Any]]:
    payload = _parse_json_object(raw)
    records_by_id = {
        str(item.get("question_id")): item
        for item in payload.get("answers", [])
        if isinstance(item, dict) and item.get("question_id") is not None
    }
    parsed: dict[str, dict[str, Any]] = {}
    for question in questions:
        item = records_by_id.get(question.question_id, {})
        answer = str(item.get("answer", "")).strip().upper()
        answer = answer if answer in {"A", "B", "C", "D"} else None
        evidence_ids = item.get("evidence_chunk_ids") or []
        if not isinstance(evidence_ids, list):
            evidence_ids = []
        evidence_ids = list(dict.fromkeys(str(value) for value in evidence_ids if str(value) in valid_ids))
        confidence = item.get("confidence")
        confidence = float(confidence) if isinstance(confidence, (int, float)) else None
        parsed[question.question_id] = {
            "answer": answer,
            "evidence_chunk_ids": evidence_ids,
            "confidence": confidence,
            "format_valid": answer is not None and (not require_evidence or bool(evidence_ids)),
        }
    return parsed


def _parse_json_object(raw: str) -> dict[str, Any]:
    text = raw.strip()
    if text.startswith("```"):
        text = re.sub(r"^```(?:json)?\s*|\s*```$", "", text, flags=re.IGNORECASE)
    start, end = text.find("{"), text.rfind("}")
    if start < 0 or end < start:
        return {}
    try:
        payload = json.loads(text[start:end + 1])
    except json.JSONDecodeError:
        return {}
    return payload if isinstance(payload, dict) else {}


def result_record(
    stage: str,
    question: Question,
    model: str,
    parsed: dict[str, Any],
    usage: dict[str, Any],
    started: float,
    finish_reason: str | None,
) -> dict[str, Any]:
    return {
        "stage": stage,
        "question_id": question.question_id,
        "model": model,
        "expected_answer": question.answer,
        "answer": parsed["answer"],
        "correct": parsed["answer"] == question.answer,
        "evidence_chunk_ids": parsed["evidence_chunk_ids"],
        "confidence": parsed["confidence"],
        "format_valid": parsed["format_valid"],
        "latency_seconds": round(time.monotonic() - started, 3),
        "usage": usage,
        "finish_reason": finish_reason,
    }


def failed_result(stage: str, question: Question, model: str, exc: Exception, started: float) -> dict[str, Any]:
    return {
        "stage": stage,
        "question_id": question.question_id,
        "model": model,
        "expected_answer": question.answer,
        "answer": None,
        "correct": False,
        "evidence_chunk_ids": [],
        "confidence": None,
        "format_valid": False,
        "latency_seconds": round(time.monotonic() - started, 3),
        "usage": {},
        "error": f"{type(exc).__name__}: {exc}",
    }


def choose_minimal_candidates(questions: list[Question], phase1: list[dict[str, Any]]) -> list[dict[str, Any]]:
    by_question: dict[str, list[dict[str, Any]]] = {}
    for result in phase1:
        by_question.setdefault(result["question_id"], []).append(result)
    candidates = []
    for question in questions:
        successful = [
            result for result in by_question.get(question.question_id, [])
            if result.get("correct") and result.get("format_valid") and result.get("evidence_chunk_ids")
        ]
        correct_model_count = sum(1 for result in by_question.get(question.question_id, []) if result.get("correct"))
        selected = min(successful, key=lambda item: (len(item["evidence_chunk_ids"]), -(item.get("confidence") or 0), item["model"])) if successful else None
        candidates.append({
            "question_id": question.question_id,
            "expected_answer": question.answer,
            "phase1_correct_model_count": correct_model_count,
            "evidence_chunk_ids": selected["evidence_chunk_ids"] if selected else [],
            "selected_from_model": selected["model"] if selected else None,
            "selected_answer": selected["answer"] if selected else None,
        })
    return candidates


def summarize(
    questions: list[Question],
    models: list[str],
    phase1: list[dict[str, Any]],
    phase2: list[dict[str, Any]],
    candidates: list[dict[str, Any]],
) -> dict[str, Any]:
    candidate_by_question = {item["question_id"]: item for item in candidates}
    phase1_by_question: dict[str, list[dict[str, Any]]] = {}
    phase2_by_question: dict[str, list[dict[str, Any]]] = {}
    for result in phase1:
        phase1_by_question.setdefault(result["question_id"], []).append(result)
    for result in phase2:
        phase2_by_question.setdefault(result["question_id"], []).append(result)

    model_metrics = {}
    for model in models:
        first = [result for result in phase1 if result["model"] == model]
        second = [result for result in phase2 if result["model"] == model]
        model_metrics[model] = {
            "phase1_accuracy": rate(first, "correct"),
            "phase2_accuracy": rate(second, "correct"),
            "phase1_format_valid_rate": rate(first, "format_valid"),
            "phase2_format_valid_rate": rate(second, "format_valid"),
            "phase1_request_count": len(first),
            "phase2_request_count": len(second),
            "usage": sum_usage(first + second),
        }

    candidate_golden = []
    for question in questions:
        question_id = question.question_id
        candidate = candidate_by_question[question_id]
        first_correct = sum(1 for item in phase1_by_question.get(question_id, []) if item.get("correct"))
        second_correct = sum(1 for item in phase2_by_question.get(question_id, []) if item.get("correct"))
        if candidate["evidence_chunk_ids"] and first_correct >= 3 and second_correct >= 3:
            candidate_golden.append({
                "question_id": question_id,
                "question": question.stem,
                "options": question.options,
                "answer": question.answer,
                "evidence_chunk_ids": candidate["evidence_chunk_ids"],
                "evidence_count": len(candidate["evidence_chunk_ids"]),
                "selected_from_model": candidate["selected_from_model"],
                "phase1_correct_model_count": first_correct,
                "phase2_correct_model_count": second_correct,
            })

    return {
        "summary": {
            "question_count": len(questions),
            "phase1_total_accuracy": rate(phase1, "correct"),
            "phase2_total_accuracy": rate(phase2, "correct"),
            "candidate_golden_count": len(candidate_golden),
            "candidate_golden_rate": round(len(candidate_golden) / len(questions), 4) if questions else 0.0,
            "golden_rule": "phase1 and phase2 each have at least 3 of 4 correct model answers",
        },
        "model_metrics": model_metrics,
        "candidate_golden": candidate_golden,
    }


def render_context(chunks: list[dict[str, Any]], character_cap: int | None = None) -> str:
    parts: list[str] = []
    used = 0
    for chunk in chunks:
        section = " > ".join(chunk.get("title_path") or []) or "未识别章节"
        block = (
            f"\n[chunk_id={chunk['chunk_id']}; document={chunk['document_name']}; page={chunk.get('page_no')}; "
            f"section={section}; type={chunk.get('block_type')}]\n{chunk['content']}\n"
        )
        if character_cap is not None and used + len(block) > character_cap:
            break
        parts.append(block)
        used += len(block)
    if not parts:
        raise ValueError("No chunks fit within the context character cap")
    return "".join(parts)


def render_questions(questions: list[Question]) -> str:
    return "\n\n".join(question.prompt_text() for question in questions)


def render_grouped_minimal_context(
    questions: list[Question],
    candidates: dict[str, dict[str, Any]],
    chunks_by_id: dict[str, dict[str, Any]],
) -> str:
    groups = []
    for question in questions:
        candidate = candidates[question.question_id]
        evidence = [chunks_by_id[chunk_id] for chunk_id in candidate["evidence_chunk_ids"]]
        groups.append(f"\n[QUESTION={question.question_id}]\n{render_context(evidence)}")
    return "".join(groups)


def safe_file_name(value: str) -> str:
    return re.sub(r"[^A-Za-z0-9._-]+", "_", value)


def batches(items: list[Question], size: int) -> list[list[Question]]:
    if size < 1:
        raise ValueError("batch_size must be at least 1")
    return [items[index:index + size] for index in range(0, len(items), size)]


def load_existing(path: Path) -> set[tuple[str, str]]:
    return {(item["question_id"], item["model"]) for item in read_jsonl(path)}


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    if not path.exists():
        return []
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def append_jsonl(path: Path, item: dict[str, Any]) -> None:
    with path.open("a", encoding="utf-8") as handle:
        handle.write(json.dumps(item, ensure_ascii=False) + "\n")


def write_jsonl(path: Path, items: list[dict[str, Any]]) -> None:
    path.write_text("".join(json.dumps(item, ensure_ascii=False) + "\n" for item in items), encoding="utf-8")


def write_json(path: Path, item: dict[str, Any]) -> None:
    path.write_text(json.dumps(item, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def fingerprint(value: Any) -> str:
    payload = json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()


def rate(items: list[dict[str, Any]], field: str) -> float:
    return round(sum(1 for item in items if item.get(field)) / len(items), 4) if items else 0.0


def sum_usage(items: list[dict[str, Any]]) -> dict[str, int]:
    totals: Counter[str] = Counter()
    for item in items:
        for key, value in (item.get("usage") or {}).items():
            if isinstance(value, int):
                totals[key] += value
    return dict(totals)


def print_progress(stage: str, question_id: str, model: str, result: dict[str, Any]) -> None:
    status = "ok" if result.get("correct") else "wrong"
    print(f"{stage} question={question_id} model={model} {status} latency={result['latency_seconds']}s", flush=True)


if __name__ == "__main__":
    main()
