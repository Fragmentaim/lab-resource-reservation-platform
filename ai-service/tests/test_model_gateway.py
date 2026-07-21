import pytest

from app.core import model_gateway


def test_model_gateway_uses_dedicated_tool_model_and_normalizes_client_policy(monkeypatch):
    monkeypatch.setattr(model_gateway.settings, "llm_api_mode", "chat_completions")
    monkeypatch.setattr(model_gateway.settings, "chat_model", "chat-model")
    monkeypatch.setattr(model_gateway.settings, "tool_calling_model", "planner-model")
    monkeypatch.setattr(model_gateway.settings, "llm_timeout_seconds", 0.2)
    monkeypatch.setattr(model_gateway.settings, "llm_max_retries", -1)

    chat = model_gateway.chat_capability()
    tool = model_gateway.require_native_tool_calling()

    assert chat.model == "chat-model"
    assert tool.model == "planner-model"
    assert tool.supports_native_tool_calling is True
    assert tool.timeout_seconds == 1.0
    assert tool.max_retries == 0


def test_model_gateway_rejects_native_tools_for_responses_mode(monkeypatch):
    monkeypatch.setattr(model_gateway.settings, "llm_api_mode", "responses")

    with pytest.raises(ValueError, match="chat_completions"):
        model_gateway.require_native_tool_calling()
