"""Rescore a saved real-Agent report from its persisted Java QA records.

Use this only to repair evaluator parsing/scoring logic.  It reads the already
executed responses by trace ID; it never invokes the model or tools again and
does not copy raw answers into the reconciled artifact.
"""

from __future__ import annotations

import argparse
import getpass
import json
from pathlib import Path

import httpx

from .run_real_agent_rag_benchmark import extract_answer_letter, login, summarize


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--base-url", default="http://127.0.0.1:8085")
    parser.add_argument("--username", default="tester")
    parser.add_argument("--password", default=None)
    args = parser.parse_args()

    report = json.loads(args.input.read_text(encoding="utf-8"))
    password = args.password or getpass.getpass("Backend password: ")
    with httpx.Client(timeout=30.0, trust_env=False) as client:
        token = login(client, args.base_url, args.username, password)
        response = client.get(
            f"{args.base_url.rstrip('/')}/knowledge/qa/records?pageNum=1&pageSize=100",
            headers={"Authorization": f"Bearer {token}"},
        )
        body = response.json()
        if response.status_code >= 400 or body.get("code") != 200:
            raise RuntimeError(f"record lookup failed: HTTP {response.status_code}")
        records = {
            str(item.get("traceId")): item
            for item in (body.get("data") or {}).get("records") or []
            if item.get("traceId")
        }

    reconciled = 0
    for result in report.get("results") or []:
        record = records.get(str(result.get("trace_id") or ""))
        if not record:
            continue
        predicted = extract_answer_letter(str(record.get("answer") or ""))
        if predicted != result.get("predicted_answer"):
            result["predicted_answer"] = predicted
            result["answer_correct"] = predicted == result.get("expected_answer")
            reconciled += 1

    results = report.get("results") or []
    report["summary"] = summarize(results)
    report["scoring_revision"] = {
        "reason": "Markdown answer-label normalization; no model or tool was re-executed.",
        "reconciled_case_count": reconciled,
        "source_report": str(args.input.resolve()),
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report["summary"], ensure_ascii=False, indent=2))
    print(args.output.resolve())
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
