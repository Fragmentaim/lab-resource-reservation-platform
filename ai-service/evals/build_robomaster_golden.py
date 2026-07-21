"""Build a reproducible RAG benchmark set from audited candidate questions.

The source candidate file contains temporary D1-Cxxxx IDs generated while
chunking the RoboMaster PDFs offline.  This tool resolves each of those IDs to
stable content hashes and document metadata, so a later ingestion into Qdrant
can be matched even though runtime chunk IDs include the document ID/version.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any

from bootstrap_golden_from_mcq import build_chunks, read_jsonl, write_json, write_jsonl


def main() -> None:
    args = parse_args()
    candidates = read_jsonl(args.candidates)
    chunks = build_chunks(args.corpus_dir)
    chunk_by_id = {chunk["chunk_id"]: chunk for chunk in chunks}
    excluded_ids = {item.strip() for item in args.exclude_question_ids.split(",") if item.strip()}

    records = []
    missing = []
    for candidate in candidates:
        question_id = str(candidate["question_id"])
        if question_id in excluded_ids:
            continue
        evidence = []
        for chunk_id in candidate.get("evidence_chunk_ids") or []:
            chunk = chunk_by_id.get(chunk_id)
            if chunk is None:
                missing.append({"question_id": question_id, "chunk_id": chunk_id})
                continue
            evidence.append({
                "offline_chunk_id": chunk_id,
                "document_key": f"D{chunk['document_index']}",
                "document_name": chunk["document_name"],
                "chunk_index": _chunk_index(chunk_id),
                "content_hash": chunk["content_hash"],
                "page_no": chunk.get("page_no"),
                "section_title": chunk.get("section_title"),
                "title_path": chunk.get("title_path") or [],
            })
        if len(evidence) != len(candidate.get("evidence_chunk_ids") or []):
            continue
        records.append({
            "benchmark_id": f"robomaster-2025-q{question_id}",
            "question_id": question_id,
            "question": candidate["question"],
            "options": candidate["options"],
            "answer": candidate["answer"],
            "expected_evidence": evidence,
            "candidate_status": "evidence_audit_required",
            "selection_trace": {
                "phase1_correct_model_count": candidate.get("phase1_correct_model_count"),
                "phase2_correct_model_count": candidate.get("phase2_correct_model_count"),
                "selected_from_model": candidate.get("selected_from_model"),
            },
        })

    output = args.output.resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    write_jsonl(output, records)
    manifest = {
        "schema_version": 1,
        "benchmark_name": "robomaster-rag-candidate-v1",
        "candidate_count": len(records),
        "excluded_question_ids": sorted(excluded_ids, key=int),
        "missing_evidence": missing,
        "corpus_dir": str(args.corpus_dir.resolve()),
        "rule": "candidate questions must be independently evidence-audited before reporting resume metrics",
    }
    write_json(output.with_suffix(".manifest.json"), manifest)
    print(json.dumps(manifest, ensure_ascii=False, indent=2))


def _chunk_index(chunk_id: str) -> int:
    return int(chunk_id.rsplit("-C", maxsplit=1)[1])


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--candidates", type=Path, required=True)
    parser.add_argument("--corpus-dir", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument(
        "--exclude-question-ids",
        default="175,178",
        help="Known unsupported candidate questions excluded before evidence audit.",
    )
    return parser.parse_args()


if __name__ == "__main__":
    main()
