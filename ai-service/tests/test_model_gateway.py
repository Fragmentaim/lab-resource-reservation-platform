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


def test_chat_completion_options_forwards_explicit_reasoning_controls(monkeypatch):
    monkeypatch.setattr(model_gateway.settings, "llm_thinking_mode", "adaptive")
    monkeypatch.setattr(model_gateway.settings, "llm_reasoning_effort", "max")

    assert model_gateway.chat_completion_options() == {
        "extra_body": {
            "thinking": {"type": "adaptive"},
            "reasoning_effort": "max",
        }
    }


def test_gateway_uses_fallback_after_primary_failure(monkeypatch):
    monkeypatch.setattr(model_gateway.settings, "llm_base_url", "https://primary.example")
    monkeypatch.setattr(model_gateway.settings, "chat_model", "primary-model")
    monkeypatch.setattr(model_gateway.settings, "llm_fallback_base_url", "https://fallback.example")
    monkeypatch.setattr(model_gateway.settings, "llm_fallback_chat_model", "fallback-model")
    monkeypatch.setattr(model_gateway.settings, "llm_circuit_failure_threshold", 1)
    monkeypatch.setattr(model_gateway, "_client_for", lambda _route: object())
    model_gateway._states.clear()
    model_gateway._clients.clear()

    def operation(_client, model):
        if model == "primary-model":
            raise TimeoutError("primary unavailable")
        return "fallback answer"

    result, route = model_gateway.invoke("chat", None, operation)

    assert result == "fallback answer"
    assert route["provider"] == "fallback"
    assert route["fallback_used"] is True
    assert model_gateway.health_snapshot()[0]["state"] == "OPEN"


def test_open_circuit_skips_primary_until_reset(monkeypatch):
    monkeypatch.setattr(model_gateway.settings, "llm_base_url", "https://primary.example")
    monkeypatch.setattr(model_gateway.settings, "chat_model", "primary-model")
    monkeypatch.setattr(model_gateway.settings, "llm_fallback_base_url", "")
    monkeypatch.setattr(model_gateway.settings, "llm_circuit_failure_threshold", 1)
    model_gateway._states.clear()
    model_gateway._clients.clear()

    with pytest.raises(model_gateway.ModelGatewayError):
        model_gateway.invoke("chat", None, lambda _client, _model: (_ for _ in ()).throw(TimeoutError("down")))

    with pytest.raises(model_gateway.ModelGatewayError, match="circuit_open"):
        model_gateway.invoke("chat", None, lambda _client, _model: "should not be called")
