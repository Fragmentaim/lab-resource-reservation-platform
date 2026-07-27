"""Run one real Markdown handoff compaction and emit an auditable report."""

import argparse
import json
import time
from pathlib import Path

from app.core.handoff_memory import HANDOFF_HEADER, normalize_handoff
from app.core.session_summarizer import summarize_session


def build_previous_handoff() -> str:
    return f"""{HANDOFF_HEADER}

## 任务线：预约方案 AURORA-18
- [用户] 当前目标：整理实验室预约条件，暂不创建真实预约。
- [用户] 当前实验室：创新实验室A，labId=LAB-01。
- [用户] 可接受周四下午；周三晚上不能安排；单次使用最多2小时。
- 当前状态：仅查询，尚未确认任何写操作。
- 缺少参数：resourceId、slotId。

## 权限线索
- [用户陈述] 当前身份为普通成员。
- [用户陈述] 可查看 RULE-PUBLIC-2026，不能查看 FINANCE-PRIVATE-09。
- 以上权限仍需服务端 PolicyContext 与 ACL 重新校验。
"""


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


def checks(handoff: str) -> dict[str, bool]:
    return {
        "valid_markdown_handoff": handoff.startswith(HANDOFF_HEADER),
        "current_lab_preserved": "制造实验室B" in handoff and "LAB-02" in handoff,
        "superseded_lab_preserved": "创新实验室A" in handoff and "LAB-01" in handoff,
        "project_preserved": "AURORA-18" in handoff,
        "resource_preserved": "焊接台-WELD-4" in handoff and "RES-12" in handoff,
        "date_and_time_constraints_preserved": all(
            value in handoff for value in ("2026-09-03", "周四下午", "周三晚上", "2小时")
        ),
        "query_only_boundary_preserved": (
            "只做资源可用性检查" in handoff
            and "不要创建预约" in handoff
            and ("未确认" in handoff or "没有确认" in handoff)
        ),
        "missing_slot_preserved": "slotId" in handoff,
        "acl_not_authoritative": "PolicyContext" in handoff or "服务端" in handoff,
        "dynamic_availability_requires_refresh": "需要重新调用工具刷新" in handoff,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()

    previous = build_previous_handoff()
    turns = new_turns()
    started = time.perf_counter()
    result = summarize_session(previous, turns)
    latency_ms = round((time.perf_counter() - started) * 1000, 2)
    handoff = normalize_handoff(result["summary"])
    assertions = checks(handoff)
    report = {
        "benchmark_type": "real_single_markdown_handoff_compaction",
        "format_version": "MARKDOWN_HANDOFF_V1",
        "input": {
            "previous_handoff": previous,
            "new_turns": turns,
        },
        "output_handoff": handoff,
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
