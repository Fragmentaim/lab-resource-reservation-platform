import pytest

from app.core import model_gateway


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

    result, route = model_gateway.invoke(None, operation)

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
        model_gateway.invoke(None, lambda _client, _model: (_ for _ in ()).throw(TimeoutError("down")))

    with pytest.raises(model_gateway.ModelGatewayError, match="circuit_open"):
        model_gateway.invoke(None, lambda _client, _model: "should not be called")
