import asyncio

import httpx
import pytest

from app.mcp_server import BackendToolClient, mcp


def _client(handler):
    return httpx.Client(base_url="http://backend.test", transport=httpx.MockTransport(handler))


def test_mcp_backend_client_forwards_process_token_without_exposing_it_in_arguments():
    captured = {}

    def handler(request):
        captured["authorization"] = request.headers.get("Authorization")
        captured["path"] = request.url.path
        return httpx.Response(200, json={"code": 200, "message": "success", "data": {"ok": True}})

    client = BackendToolClient("http://backend.test", "process-owned-token", client=_client(handler))

    assert client.get("/knowledge/tools/reservation-context/me") == {"ok": True}
    assert captured == {
        "authorization": "Bearer process-owned-token",
        "path": "/knowledge/tools/reservation-context/me",
    }


def test_mcp_backend_client_refuses_missing_process_token_before_sending_request():
    client = BackendToolClient("http://backend.test", "", client=_client(lambda _: pytest.fail("request should not be sent")))

    with pytest.raises(RuntimeError, match="MCP_ACCESS_TOKEN"):
        client.get("/knowledge/tools/reservation-context/me")


def test_mcp_backend_client_propagates_backend_business_rejection():
    client = BackendToolClient(
        "http://backend.test", "token",
        client=_client(lambda _: httpx.Response(200, json={"code": 403, "message": "forbidden", "data": None})),
    )

    with pytest.raises(RuntimeError, match="forbidden"):
        client.get("/knowledge/tools/reservation-context/me")


def test_mcp_server_discovers_only_non_mutating_platform_capabilities():
    tools = asyncio.run(mcp.list_tools())

    assert [tool.name for tool in tools] == [
        "get_my_reservation_context",
        "find_resource_availability",
        "preview_reservation_cancellation",
        "ask_lab_knowledge",
    ]
