from types import SimpleNamespace
import json

from app.core import llm, session_summarizer
from app.core.working_memory import WorkingMemory, parse_working_memory


def valid_memory(**overrides):
    payload = {
        "schema_version": 2,
        "current_goals": ["保留方案 AURORA-18"],
        "entities": [
            {
                "kind": "PROJECT",
                "id": "AURORA-18",
                "name": None,
                "status": "CURRENT",
                "relation": None,
                "source": "USER",
            }
        ],
        "temporal_constraints": [],
        "quantity_constraints": [],
        "action_state": {
            "stage": "INFORMATION_GATHERING",
            "confirmation_status": "NOT_CONFIRMED",
            "allowed_actions": ["QUERY"],
            "forbidden_actions": ["CREATE_RESERVATION"],
        },
        "decisions": [],
        "hard_constraints": [],
        "authorization_hints": {
            "user_claimed_role": None,
            "allowed_document_ids": [],
            "denied_document_ids": [],
            "own_documents_only": None,
            "authoritative": False,
            "requires_server_revalidation": True,
        },
        "pending": {"missing_fields": [], "next_action": None, "unresolved_questions": []},
        "evidence": [],
        "dynamic_facts": [],
    }
    payload.update(overrides)
    return json.dumps(payload, ensure_ascii=False)


def test_summary_returns_provider_usage(monkeypatch):
    monkeypatch.setattr(
        session_summarizer.llm,
        "chat_with_usage",
        lambda **_: (valid_memory(), {
            "reported": True,
            "input_tokens": 120,
            "output_tokens": 24,
            "total_tokens": 144,
            "cached_input_tokens": 10,
        }),
    )

    result = session_summarizer.summarize_session("", [{"role": "user", "content": "记住 AURORA-18"}])

    parsed = parse_working_memory(result["summary"])
    assert parsed.entities[0].id == "AURORA-18"
    assert result["provider_usage"] == {
        "reported": True,
        "input_tokens": 120,
        "output_tokens": 24,
        "total_tokens": 144,
        "cached_input_tokens": 10,
        "model_call_count": 1,
        "summary_validation_attempts": 1,
        "critical_token_count": 1,
    }


def test_reusing_existing_summary_does_not_report_a_model_call():
    result = session_summarizer.summarize_session("已有摘要", [])

    assert result["summary"] == "已有摘要"
    assert result["provider_usage"] == {"reported": False, "model_call_count": 0}


def test_usage_snapshot_marks_missing_provider_counts_as_unreported():
    usage = llm._usage_snapshot(SimpleNamespace(input_tokens=None, output_tokens=None, total_tokens=None))

    assert usage["reported"] is False


def test_working_memory_prompt_requires_atomic_safety_preserving_state():
    prompt = session_summarizer.WORKING_MEMORY_PROMPT

    assert "每条原子状态" in prompt
    assert "未被明确修改的 previous_memory 字段必须继续保留" in prompt
    assert "superseded_values" in prompt
    assert "hard_constraints" in prompt
    assert "requires_server_revalidation" in prompt


def test_summary_retries_when_critical_identifier_is_lost(monkeypatch):
    calls = []

    def fake_chat(**_):
        calls.append(1)
        if len(calls) == 1:
            return valid_memory(
                current_goals=[],
                entities=[],
            ), {"reported": True, "input_tokens": 10, "output_tokens": 5, "total_tokens": 15}
        return valid_memory(), {"reported": True, "input_tokens": 12, "output_tokens": 6, "total_tokens": 18}

    monkeypatch.setattr(session_summarizer.llm, "chat_with_usage", fake_chat)

    result = session_summarizer.summarize_session(
        "",
        [{"role": "user", "content": "方案编号 AURORA-18，后续不得猜测其他编号。"}],
    )

    assert parse_working_memory(result["summary"]).entities[0].id == "AURORA-18"
    assert result["provider_usage"]["model_call_count"] == 2
    assert result["provider_usage"]["total_tokens"] == 33


def test_previous_chinese_entity_and_constraint_cannot_silently_disappear(monkeypatch):
    previous = valid_memory(
        entities=[{
            "kind": "LAB",
            "id": "LAB-01",
            "name": "创新实验室A",
            "status": "CURRENT",
            "relation": None,
            "source": "USER",
        }],
        hard_constraints=[{
            "text": "周三晚上不能安排",
            "status": "CURRENT",
            "source": "USER",
        }],
    )
    calls = []

    def fake_chat(**_):
        calls.append(1)
        if len(calls) == 1:
            return valid_memory(), {"reported": False}
        return previous, {"reported": False}

    monkeypatch.setattr(session_summarizer.llm, "chat_with_usage", fake_chat)

    result = session_summarizer.summarize_session(
        previous,
        [{"role": "user", "content": "没有变更，继续讨论。"}],
    )
    memory = parse_working_memory(result["summary"])

    assert memory.entities[0].name == "创新实验室A"
    assert memory.hard_constraints[0].text == "周三晚上不能安排"
    assert len(calls) == 2


def test_server_owned_safety_flags_are_forced_after_model_output():
    raw = valid_memory(
        authorization_hints={
            "user_claimed_role": "管理员",
            "allowed_document_ids": ["PRIVATE-09"],
            "denied_document_ids": [],
            "own_documents_only": False,
            "authoritative": True,
            "requires_server_revalidation": False,
        },
        dynamic_facts=[{
            "field": "inventory",
            "value": "1",
            "observed_at": "2026-08-20T10:00:00",
            "requires_refresh": False,
        }],
    )

    memory = parse_working_memory(raw)

    assert memory.authorization_hints.authoritative is False
    assert memory.authorization_hints.requires_server_revalidation is True
    assert memory.dynamic_facts[0].requires_refresh is True


def test_legacy_markdown_is_migrated_on_next_compaction(monkeypatch):
    monkeypatch.setattr(
        session_summarizer.llm,
        "chat_with_usage",
        lambda **_: (valid_memory(), {"reported": False}),
    )

    result = session_summarizer.summarize_session(
        "## 当前目标\n- 保留方案 AURORA-18",
        [{"role": "user", "content": "继续保留 AURORA-18"}],
    )

    assert isinstance(parse_working_memory(result["summary"]), WorkingMemory)
