"""Compare full-history, sliding-window and summary-memory conversation reports.

The input reports contain only sanitized evaluation signals. This script never
replays prompts or model answers, so it can be safely used on published runs.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any


def load(path: Path) -> dict[str, Any]:
    payload = json.loads(path.read_text(encoding="utf-8"))
    if payload.get("partial"):
        raise ValueError(f"partial report is not comparable: {path}")
    return payload


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--full", required=True, type=Path)
    parser.add_argument("--sliding", required=True, type=Path)
    parser.add_argument("--summary", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    reports = {"full_history": load(args.full), "sliding_window": load(args.sliding), "summary_memory": load(args.summary)}
    rows: dict[str, Any] = {}
    for name, report in reports.items():
        summary = report.get("summary") or {}
        usage = summary.get("provider_usage") or {}
        rows[name] = {
            "context_strategy": report.get("context_strategy"),
            "conversation_count": summary.get("conversation_count", 0),
            "key_constraint_retention_rate": summary.get("key_constraint_retention_rate", 0),
            "reference_resolution_accuracy": summary.get("reference_resolution_accuracy", 0),
            "multi_turn_task_success_rate": summary.get("multi_turn_task_success_rate", 0),
            "summary_trigger_rate": summary.get("summary_trigger_rate", 0),
            "history_token_reduction_rate": summary.get("history_token_reduction_rate", 0),
            "provider_usage": usage,
            "turn_latency_ms": summary.get("turn_latency_ms", {}),
        }
    baseline_input = int(rows["full_history"]["provider_usage"].get("input_tokens") or 0)
    compact_input = int(rows["summary_memory"]["provider_usage"].get("input_tokens") or 0)
    payload = {
        "benchmark_type": "multi_turn_context_strategy_comparison",
        "suite_version": reports["summary_memory"].get("suite_version"),
        "rows": rows,
        "summary_memory_vs_full_history_input_token_reduction_rate": round(1 - compact_input / baseline_input, 4)
        if baseline_input else None,
        "scope": "Same built-in user-turn suite and model configuration. Full-history is a quality upper bound; sliding-window is the no-memory baseline; summary-memory is the production strategy under the constrained profile.",
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(payload, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
