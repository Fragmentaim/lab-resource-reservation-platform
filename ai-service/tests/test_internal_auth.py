from fastapi.testclient import TestClient

from app.config import settings
from app.main import app


def test_internal_ai_routes_require_service_token(monkeypatch):
    monkeypatch.setattr(settings, "ai_service_token", "test-internal-token")
    client = TestClient(app)

    assert client.get("/api/v1/ai/models").status_code == 401
    assert client.get("/api/v1/ai/models", headers={"X-AI-Service-Token": "test-internal-token"}).status_code == 200
    assert client.get("/api/v1/ai/health").status_code in {200, 503}
