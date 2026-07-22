"""Audit ACL filtering using the real retrieval and chunk-open endpoints.

This deliberately does not call a chat model.  The security invariant is
checked against the document IDs returned by retrieval and by the raw chunk
open path, not inferred from an assistant answer.
"""

from __future__ import annotations

import argparse
import json
import time
from pathlib import Path
from typing import Any

import httpx


def _call(client: httpx.Client, url: str, token: str, payload: dict[str, Any]) -> dict[str, Any]:
    response = client.post(url, headers={"X-AI-Service-Token": token}, json=payload)
    if response.status_code >= 400:
        raise RuntimeError(f"HTTP {response.status_code}: {response.text[:240]}")
    body = response.json()
    return body if isinstance(body, dict) else {}


def _audit_case(client: httpx.Client, base_url: str, token: str, case: dict[str, Any]) -> dict[str, Any]:
    allowed = {int(value) for value in case.get("allowedDocumentIds") or []}
    expected = {int(value) for value in case.get("expectedRelevantDocumentIds") or []}
    started = time.perf_counter()
    try:
        retrieved_body = _call(client, f"{base_url}/api/v1/ai/qa/retrieve", token, {
            "question": case.get("query") or "",
            "document_ids": sorted(allowed),
            "top_k": 20,
        })
        candidates = retrieved_body.get("candidates") if isinstance(retrieved_body, dict) else []
        candidates = candidates if isinstance(candidates, list) else []
        candidate_doc_ids = sorted({int(item["document_id"]) for item in candidates
                                    if isinstance(item, dict) and item.get("document_id") is not None})
        chunk_uids = [str(item.get("chunk_uid")) for item in candidates
                      if isinstance(item, dict) and item.get("chunk_uid")]
        opened_body = _call(client, f"{base_url}/api/v1/ai/qa/chunks/open", token, {
            "chunk_uids": chunk_uids,
            "document_ids": sorted(allowed),
        })
        opened = opened_body.get("chunks") if isinstance(opened_body, dict) else []
        opened = opened if isinstance(opened, list) else []
        opened_doc_ids = sorted({int(item["document_id"]) for item in opened
                                 if isinstance(item, dict) and item.get("document_id") is not None})
        # Deliberately add an out-of-scope sentinel to the open request. If a
        # chunk is accessible, the server must still constrain it to `allowed`.
        tampered_body = _call(client, f"{base_url}/api/v1/ai/qa/chunks/open", token, {
            "chunk_uids": chunk_uids,
            "document_ids": sorted(allowed | {999999}),
        })
        tampered = tampered_body.get("chunks") if isinstance(tampered_body, dict) else []
        tampered = tampered if isinstance(tampered, list) else []
        tampered_doc_ids = sorted({int(item["document_id"]) for item in tampered
                                   if isinstance(item, dict) and item.get("document_id") is not None})
        retrieval_leak = sorted(set(candidate_doc_ids) - allowed)
        open_leak = sorted(set(opened_doc_ids) - allowed)
        tampered_leak = sorted(set(tampered_doc_ids) - allowed)
        unauthorized = bool(case.get("unauthorizedAttempt"))
        legal_expected = bool(expected)
        return {
            "case_id": case.get("caseId"),
            "scenario": case.get("scenario"),
            "allowed_document_ids": sorted(allowed),
            "expected_relevant_document_ids": sorted(expected),
            "candidate_document_ids": candidate_doc_ids,
            "opened_document_ids": opened_doc_ids,
            "tampered_open_document_ids": tampered_doc_ids,
            "candidate_count": len(candidates),
            "opened_chunk_count": len(opened),
            "retrieval_leak_document_ids": retrieval_leak,
            "open_leak_document_ids": open_leak,
            "tampered_open_leak_document_ids": tampered_leak,
            "retrieval_acl_safe": not retrieval_leak,
            "open_acl_safe": not open_leak and not tampered_leak,
            "legal_relevant_recall": bool(expected & set(candidate_doc_ids)) if legal_expected else None,
            "unauthorized_blocked": (not candidate_doc_ids and not opened_doc_ids) if unauthorized else None,
            "latency_ms": round((time.perf_counter() - started) * 1000, 2),
            "error": None,
        }
    except Exception as exc:
        return {
            "case_id": case.get("caseId"),
            "scenario": case.get("scenario"),
            "allowed_document_ids": sorted(allowed),
            "expected_relevant_document_ids": sorted(expected),
            "retrieval_acl_safe": None,
            "open_acl_safe": None,
            "legal_relevant_recall": None,
            "unauthorized_blocked": None,
            "latency_ms": round((time.perf_counter() - started) * 1000, 2),
            "error": str(exc),
        }


def _summary(results: list[dict[str, Any]]) -> dict[str, Any]:
    valid = [item for item in results if not item.get("error")]
    legal = [item for item in valid if item.get("expected_relevant_document_ids")]
    unauthorized = [item for item in valid if item.get("unauthorized_blocked") is not None]
    retrieval_safe = [item for item in valid if item.get("retrieval_acl_safe") is True]
    open_safe = [item for item in valid if item.get("open_acl_safe") is True]
    legal_recall = [item for item in legal if item.get("legal_relevant_recall") is True]
    blocked = [item for item in unauthorized if item.get("unauthorized_blocked") is True]
    return {
        "case_count": len(results),
        "valid_case_count": len(valid),
        "retrieval_acl_safe_rate": round(len(retrieval_safe) / len(valid), 4) if valid else 0.0,
        "raw_chunk_open_acl_safe_rate": round(len(open_safe) / len(valid), 4) if valid else 0.0,
        "unauthorized_document_leak_rate": round(1 - len(retrieval_safe) / len(valid), 4) if valid else 0.0,
        "unauthorized_request_block_rate": round(len(blocked) / len(unauthorized), 4) if unauthorized else 0.0,
        "legal_document_recall_rate": round(len(legal_recall) / len(legal), 4) if legal else 0.0,
        "legal_case_count": len(legal),
        "unauthorized_case_count": len(unauthorized),
        "failed_cases": [item.get("case_id") for item in results if item.get("error")],
        "scope": "Document ID invariant checked before model generation; no raw document content is written.",
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="Audit document ACL at retrieval and raw chunk-open boundaries.")
    parser.add_argument("--suite", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--base-url", default="http://127.0.0.1:8005")
    parser.add_argument("--token", required=True)
    args = parser.parse_args()
    suite = json.loads(args.suite.read_text(encoding="utf-8"))
    cases = list(suite.get("acl_cases") or [])
    results: list[dict[str, Any]] = []
    partial = args.output.with_suffix(args.output.suffix + ".partial")
    with httpx.Client(timeout=60, trust_env=False) as client:
        for case in cases:
            results.append(_audit_case(client, args.base_url.rstrip("/"), args.token, case))
            partial.parent.mkdir(parents=True, exist_ok=True)
            partial.write_text(json.dumps({"partial": True, "results": results}, ensure_ascii=False, indent=2), encoding="utf-8")
    output = {
        "suite_version": suite.get("task_suite_version"),
        "benchmark_type": "raw_qdrant_acl_audit",
        "partial": False,
        "summary": _summary(results),
        "results": results,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(output, ensure_ascii=False, indent=2), encoding="utf-8")
    if partial.exists():
        partial.unlink()
    print(json.dumps(output["summary"], ensure_ascii=False, indent=2))
    print(args.output.resolve())
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
