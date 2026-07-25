import importlib.util
import json
from pathlib import Path

from app.core.token_counter import count_tokens


AI_SERVICE_ROOT = Path(__file__).resolve().parents[1]
RUNNER_PATH = AI_SERVICE_ROOT / "evals" / "run_conversation_benchmark.py"
SUITE_PATH = AI_SERVICE_ROOT / "evals" / "suites" / "context-compaction-v2-8.json"
COMPLEX_SUITE_PATH = AI_SERVICE_ROOT / "evals" / "suites" / "context-compaction-v3-complex-4.json"


def _load_runner():
    spec = importlib.util.spec_from_file_location("conversation_benchmark_runner", RUNNER_PATH)
    module = importlib.util.module_from_spec(spec)
    assert spec and spec.loader
    spec.loader.exec_module(module)
    return module


def test_context_compaction_suite_has_eight_reproducible_long_sessions():
    runner = _load_runner()
    suite = json.loads(SUITE_PATH.read_text(encoding="utf-8"))
    cases = suite["conversation_cases"]

    assert len(cases) == 8
    assert all(case["requiresSummary"] for case in cases)
    assert all(case.get("expectedConstraints") for case in cases)

    rendered = {case["caseId"]: runner._user_turns(case) for case in cases}
    assert all(sum(count_tokens(turn) for turn in turns) >= 60_000 for turns in rendered.values())

    # The deterministic filler must not contain the case-specific answer keys.
    for case in cases:
        filler = runner._neutral_context_notes(case["caseId"], 1, 3)
        for expected in case["expectedConstraints"].values():
            assert str(expected) not in filler


def test_usage_projection_keeps_summary_calls_in_the_report():
    runner = _load_runner()

    usage = runner._usage({
        "reported": True,
        "model_round_count": 3,
        "agent_model_round_count": 2,
        "summary_call_count": 1,
        "input_tokens": 1200,
        "output_tokens": 180,
        "total_tokens": 1380,
        "cached_input_tokens": 300,
    })

    assert usage["summary_call_count"] == 1
    assert usage["agent_model_round_count"] == 2


def test_markdown_handoff_complex_suite_has_parallel_and_safety_cases():
    runner = _load_runner()
    suite = json.loads(COMPLEX_SUITE_PATH.read_text(encoding="utf-8"))
    cases = suite["conversation_cases"]

    assert suite["conversation_suite_version"] == "context-compaction-v3-complex-4"
    assert len(cases) == 4
    assert all(case["requiresSummary"] and case.get("expectedConstraints") for case in cases)
    assert {case["caseId"] for case in cases} == {
        "reservation-rag-switch-01",
        "parallel-resource-approval-02",
        "dynamic-fact-acl-03",
        "cancel-resume-reference-04",
    }

    rendered = {case["caseId"]: runner._user_turns(case) for case in cases}
    assert all(sum(count_tokens(turn) for turn in turns) >= 60_000 for turns in rendered.values())

    for case in cases:
        filler = runner._neutral_context_notes(case["caseId"], 1, 3)
        for expected in case["expectedConstraints"].values():
            assert str(expected) not in filler
