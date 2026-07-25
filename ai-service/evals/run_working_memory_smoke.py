"""Run one real structured Working Memory compaction and emit an auditable report."""

import argparse
import json
import time
from pathlib import Path

from app.core.session_summarizer import summarize_session
from app.core.working_memory import (
    ActionState,
    AuthorizationHints,
    ConstraintState,
    EntityState,
    PendingState,
    TemporalConstraint,
    WorkingMemory,
    parse_working_memory,
)


def build_previous_memory() -> WorkingMemory:
    return WorkingMemory(
        current_goals=["为方案 AURORA-18 整理实验室预约条件，暂不创建真实预约"],
        entities=[
            EntityState(kind="PROJECT", id="AURORA-18", status="CURRENT", source="USER"),
            EntityState(kind="LAB", id="LAB-01", name="创新实验室A", status="CURRENT", source="USER"),
        ],
        temporal_constraints=[
            TemporalConstraint(kind="ACCEPTABLE_PERIOD", value="周四下午"),
            TemporalConstraint(kind="EXCLUDED_PERIOD", value="周三晚上"),
            TemporalConstraint(kind="DURATION", value="最多2小时"),
        ],
        action_state=ActionState(
            stage="QUERY_ONLY",
            confirmation_status="NOT_CONFIRMED",
            allowed_actions=["QUERY", "AVAILABILITY_CHECK"],
            forbidden_actions=["CREATE_RESERVATION", "CANCEL_RESERVATION"],
        ),
        hard_constraints=[
            ConstraintState(text="周三晚上不能安排", source="USER"),
            ConstraintState(text="单次使用最多2小时", source="USER"),
            ConstraintState(text="未经用户明确确认不得创建真实预约", source="USER"),
        ],
        authorization_hints=AuthorizationHints(
            user_claimed_role="普通成员",
            allowed_document_ids=["RULE-PUBLIC-2026"],
            denied_document_ids=["FINANCE-PRIVATE-09"],
        ),
        pending=PendingState(
            missing_fields=["resourceId", "slotId"],
            next_action="等待用户明确资源并查询可用时段",
        ),
    )


def new_turns() -> list[dict]:
    return [
        {
            "role": "user",
            "content": (
                "把目标实验室从创新实验室A改为制造实验室B，新的labId=LAB-02，"
                "创新实验室A不再考虑，但方案AURORA-18保持不变。"
            ),
        },
        {
            "role": "assistant",
            "content": "已记录实验室替换关系，尚未执行任何预约写入。",
        },
        {
            "role": "user",
            "content": (
                "要查询的资源是焊接台-WELD-4，resourceId=RES-12；目标日期为2026-09-03，"
                "仍然只接受周四下午、排除周三晚上、最多2小时。slotId还不知道，"
                "现在只做资源可用性检查，不要创建预约，也没有确认任何写操作。"
            ),
        },
        {
            "role": "assistant",
            "content": "下一步需要查询实时可用性并等待用户选择slotId。",
        },
        {
            "role": "user",
            "content": (
                "上次查询显示还有1个名额，但这个结果可能已过期；另外我只是普通成员，"
                "只能看RULE-PUBLIC-2026，不能看FINANCE-PRIVATE-09。"
            ),
        },
    ]


def checks(memory: WorkingMemory) -> dict[str, bool]:
    raw = memory.compact_json()
    return {
        "current_lab_preserved": "制造实验室B" in raw and "LAB-02" in raw,
        "superseded_lab_preserved": "创新实验室A" in raw and "LAB-01" in raw,
        "project_preserved": "AURORA-18" in raw,
        "resource_preserved": "焊接台-WELD-4" in raw and "RES-12" in raw,
        "date_and_time_constraints_preserved": all(
            value in raw for value in ("2026-09-03", "周四下午", "周三晚上", "2小时")
        ),
        "query_only_boundary_preserved": (
            memory.action_state.stage in {"QUERY_ONLY", "AVAILABILITY_CHECK"}
            and "CREATE_RESERVATION" in memory.action_state.forbidden_actions
            and memory.action_state.confirmation_status == "NOT_CONFIRMED"
        ),
        "missing_slot_preserved": "slotId" in memory.pending.missing_fields,
        "acl_not_authoritative": (
            memory.authorization_hints.authoritative is False
            and memory.authorization_hints.requires_server_revalidation is True
        ),
        "dynamic_availability_requires_refresh": any(
            fact.field and fact.requires_refresh for fact in memory.dynamic_facts
        ),
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()

    previous = build_previous_memory()
    turns = new_turns()
    started = time.perf_counter()
    result = summarize_session(previous.compact_json(), turns)
    latency_ms = round((time.perf_counter() - started) * 1000, 2)
    memory = parse_working_memory(result["summary"])
    assertions = checks(memory)
    report = {
        "benchmark_type": "real_single_working_memory_compaction",
        "schema_version": 2,
        "input": {
            "previous_memory": previous.model_dump(exclude_none=True),
            "new_turns": turns,
        },
        "output_memory": memory.model_dump(exclude_none=True),
        "checks": assertions,
        "passed": all(assertions.values()),
        "latency_ms": latency_ms,
        "summary_tokens": result["summary_tokens"],
        "provider_usage": result["provider_usage"],
    }
    rendered = json.dumps(report, ensure_ascii=False, indent=2)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(rendered, encoding="utf-8")
    print(rendered)
    return 0 if report["passed"] else 2


if __name__ == "__main__":
    raise SystemExit(main())
