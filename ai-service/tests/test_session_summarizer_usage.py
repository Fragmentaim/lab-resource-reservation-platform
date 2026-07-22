from types import SimpleNamespace

from app.core import llm, session_summarizer


def test_summary_returns_provider_usage(monkeypatch):
    monkeypatch.setattr(
        session_summarizer.llm,
        "chat_with_usage",
        lambda **_: ("## 已确认事实\n- 方案编号：AURORA-18", {
            "reported": True,
            "input_tokens": 120,
            "output_tokens": 24,
            "total_tokens": 144,
            "cached_input_tokens": 10,
        }),
    )

    result = session_summarizer.summarize_session("", [{"role": "user", "content": "记住 AURORA-18"}])

    assert result["summary"].startswith("## 已确认事实")
    assert result["provider_usage"] == {
        "reported": True,
        "input_tokens": 120,
        "output_tokens": 24,
        "total_tokens": 144,
        "cached_input_tokens": 10,
        "model_call_count": 1,
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

    assert "每条列表只写一个原子状态" in prompt
    assert "未被明确撤销的旧摘要信息默认继续保留" in prompt
    assert "当前决定与覆盖关系" in prompt
    assert "强约束、禁止与权限" in prompt
    assert "安全边界与确认条件" in prompt
