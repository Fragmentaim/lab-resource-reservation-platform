from types import SimpleNamespace

import pytest

from app.core import llm, session_summarizer
from app.core.handoff_memory import (
    HANDOFF_HEADER,
    is_legacy_working_memory,
    normalize_handoff,
    protected_anchors,
)


def valid_handoff(*lines: str) -> str:
    body = lines or (
        "## 当前目标",
        "- [用户] 继续处理方案 AURORA-18。",
        "## 当前约束",
        "- [用户] 不要创建真实预约。",
    )
    return "\n".join((HANDOFF_HEADER, *body))


def legacy_memory() -> str:
    return (
        '{"schema_version":2,"current_goals":["保留方案 AURORA-18"],'
        '"entities":[{"kind":"PROJECT","id":"AURORA-18","name":"极光计划","status":"CURRENT",'
        '"relation":null,"source":"USER"}],"temporal_constraints":[],"quantity_constraints":[],'
        '"action_state":{"stage":"QUERY_ONLY","confirmation_status":"NOT_CONFIRMED",'
        '"allowed_actions":["QUERY"],"forbidden_actions":["CREATE_RESERVATION"]},'
        '"decisions":[],"hard_constraints":[{"text":"不要创建真实预约","status":"CURRENT","source":"USER"}],'
        '"authorization_hints":{"user_claimed_role":"普通成员","allowed_document_ids":[],'
        '"denied_document_ids":[],"own_documents_only":null,"authoritative":false,'
        '"requires_server_revalidation":true},"pending":{"missing_fields":[],"next_action":null,'
        '"unresolved_questions":[]},"evidence":[],"dynamic_facts":[]}'
    )


def test_summary_returns_markdown_and_provider_usage(monkeypatch):
    monkeypatch.setattr(
        session_summarizer.llm,
        "chat_with_usage",
        lambda **_: (valid_handoff(), {
            "reported": True,
            "input_tokens": 120,
            "output_tokens": 24,
            "total_tokens": 144,
            "cached_input_tokens": 10,
        }),
    )

    result = session_summarizer.summarize_session(
        "",
        [{"role": "user", "content": "记住 AURORA-18，不要创建真实预约。"}],
    )

    assert result["summary"].startswith(HANDOFF_HEADER)
    assert "AURORA-18" in result["summary"]
    assert result["provider_usage"] == {
        "reported": True,
        "input_tokens": 120,
        "output_tokens": 24,
        "total_tokens": 144,
        "cached_input_tokens": 10,
        "model_call_count": 1,
        "summary_validation_attempts": 1,
        "critical_token_count": 2,
        "protected_anchor_count": 2,
        "summary_format": "MARKDOWN_HANDOFF_V1",
    }


def test_reusing_existing_summary_does_not_report_a_model_call():
    result = session_summarizer.summarize_session("已有摘要", [])

    assert result["summary"] == "已有摘要"
    assert result["provider_usage"] == {"reported": False, "model_call_count": 0}


def test_usage_snapshot_marks_missing_provider_counts_as_unreported():
    usage = llm._usage_snapshot(SimpleNamespace(input_tokens=None, output_tokens=None, total_tokens=None))

    assert usage["reported"] is False


def test_handoff_prompt_is_extensible_and_safety_preserving():
    prompt = session_summarizer.HANDOFF_PROMPT

    assert HANDOFF_HEADER in prompt
    assert "可以按当前任务自由增加" in prompt
    assert "按任务线分别记录" in prompt
    assert "protected_anchors" in prompt
    assert "需要重新调用工具刷新" in prompt
    assert "不能扩大服务端 PolicyContext 或 ACL" in prompt
    assert "不要把用户意图写成已执行结果" in prompt


def test_summary_retries_when_protected_identifier_is_lost(monkeypatch):
    calls = []

    def fake_chat(**_):
        calls.append(1)
        if len(calls) == 1:
            return valid_handoff(
                "## 当前目标",
                "- 继续整理预约方案。",
                "## 当前约束",
                "- 不要创建真实预约。",
            ), {"reported": True, "input_tokens": 10, "output_tokens": 5, "total_tokens": 15}
        return valid_handoff(
            "## 当前目标",
            "- [用户] 继续处理方案 AURORA-18。",
            "## 当前约束",
            "- [用户] 后续不得猜测其他编号。",
            "- [用户] 不要创建真实预约。",
        ), {"reported": True, "input_tokens": 12, "output_tokens": 6, "total_tokens": 18}

    monkeypatch.setattr(session_summarizer.llm, "chat_with_usage", fake_chat)

    result = session_summarizer.summarize_session(
        "",
        [{"role": "user", "content": "方案编号 AURORA-18，后续不得猜测其他编号。不要创建真实预约。"}],
    )

    assert "AURORA-18" in result["summary"]
    assert result["provider_usage"]["model_call_count"] == 2
    assert result["provider_usage"]["total_tokens"] == 33


def test_previous_chinese_entity_and_constraint_cannot_silently_disappear(monkeypatch):
    previous = valid_handoff(
        "## 预约信息",
        "- [用户] 当前实验室为创新实验室A，labId=LAB-01。",
        "## 当前约束",
        "- [用户] 周三晚上不能安排。",
    )
    calls = []

    def fake_chat(**_):
        calls.append(1)
        if len(calls) == 1:
            return valid_handoff(
                "## 当前目标",
                "- 继续讨论 LAB-01。",
            ), {"reported": False}
        return previous, {"reported": False}

    monkeypatch.setattr(session_summarizer.llm, "chat_with_usage", fake_chat)

    result = session_summarizer.summarize_session(
        previous,
        [{"role": "user", "content": "没有变更，继续讨论。"}],
    )

    assert "创新实验室A" in result["summary"]
    assert "LAB-01" in result["summary"]
    assert "周三晚上不能安排" in result["summary"]
    assert len(calls) == 2


def test_legacy_json_is_migrated_on_next_compaction(monkeypatch):
    assert is_legacy_working_memory(legacy_memory())
    migrated = valid_handoff(
        "## 当前目标",
        "- [用户] 保留方案 AURORA-18（极光计划）。",
        "## 当前约束",
        "- [用户] 不要创建真实预约。",
        "- [系统线索] 当前角色为普通成员，权限仍需服务端重新校验。",
        "## 工具与操作",
        "- 只允许 QUERY，禁止 CREATE_RESERVATION。",
    )
    monkeypatch.setattr(
        session_summarizer.llm,
        "chat_with_usage",
        lambda **_: (migrated, {"reported": False}),
    )

    result = session_summarizer.summarize_session(
        legacy_memory(),
        [{"role": "user", "content": "继续保留 AURORA-18，不要创建真实预约。"}],
    )

    assert result["summary"] == migrated
    assert not result["summary"].lstrip().startswith("{")


def test_markdown_can_add_unseen_business_sections(monkeypatch):
    output = valid_handoff(
        "## 任务线：设备借用审批",
        "- [用户] 借用设备示波器-ORBIT-01。",
        "- 当前状态：等待实验室管理员审批。",
        "## 当前约束",
        "- [用户] 未确认前不要提交真实借用申请。",
    )
    monkeypatch.setattr(session_summarizer.llm, "chat_with_usage", lambda **_: (output, {"reported": False}))

    result = session_summarizer.summarize_session(
        "",
        [{"role": "user", "content": "借用设备示波器-ORBIT-01，未确认前不要提交真实借用申请。"}],
    )

    assert "任务线：设备借用审批" in result["summary"]
    assert "示波器-ORBIT-01" in result["summary"]


def test_parallel_tasks_keep_separate_identifiers(monkeypatch):
    output = valid_handoff(
        "## 任务线：预约",
        "- [用户] 方案 AURORA-18 等待选择 slotId。",
        "## 任务线：规则问答",
        "- [知识证据] 文档 RULE-PUBLIC-2026，chunk-rm-17，需要基于正文回答。",
        "## 当前约束",
        "- [用户] 不要创建真实预约。",
    )
    monkeypatch.setattr(session_summarizer.llm, "chat_with_usage", lambda **_: (output, {"reported": False}))

    result = session_summarizer.summarize_session(
        "",
        [
            {"role": "user", "content": "预约方案是 AURORA-18，slotId 还不知道，不要创建真实预约。"},
            {"role": "assistant", "content": "已记录，当前等待选择时段。"},
            {"role": "user", "content": "另一个问题依据 RULE-PUBLIC-2026 的 chunk-rm-17 回答。"},
        ],
    )

    assert result["summary"].count("## 任务线") == 2
    assert all(value in result["summary"] for value in ("AURORA-18", "RULE-PUBLIC-2026", "chunk-rm-17"))


def test_two_invalid_outputs_fail_without_returning_a_replacement(monkeypatch):
    monkeypatch.setattr(
        session_summarizer.llm,
        "chat_with_usage",
        lambda **_: ("这不是合法交接记录", {"reported": False}),
    )

    with pytest.raises(ValueError, match="Markdown 会话交接校验失败"):
        session_summarizer.summarize_session(
            valid_handoff(),
            [{"role": "user", "content": "继续保留 AURORA-18，不要创建真实预约。"}],
        )


def test_anchor_extraction_covers_identifiers_time_quantity_and_safety():
    anchors = protected_anchors(
        valid_handoff("## 当前约束", "- 周三晚上不能安排。"),
        [{
            "role": "user",
            "content": (
                "resourceId=RES-12，目标日期2026-09-03，14:30开始，最多2小时，"
                "设备为焊接台-WELD-4；没有明确确认前不要创建预约。"
            ),
        }],
    )

    assert all(value in anchors for value in (
        "RES-12", "2026-09-03", "14:30", "2小时", "焊接台-WELD-4",
        "周三晚上不能安排", "没有明确确认前不要创建预约",
    ))


def test_normalizer_rejects_json_and_accepts_markdown_code_fence():
    with pytest.raises(ValueError):
        normalize_handoff(legacy_memory())
    with pytest.raises(ValueError):
        normalize_handoff("下面是摘要：\n" + valid_handoff())

    assert normalize_handoff(f"```markdown\n{valid_handoff()}\n```") == valid_handoff()
