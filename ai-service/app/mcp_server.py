"""MCP sidecar that forwards authenticated, non-mutating assistant capabilities to Java."""

import os
from typing import Any, Optional

import httpx
from mcp.server.fastmcp import FastMCP

from app.config import settings


class BackendToolClient:
    """Keeps the bearer credential in the process environment, never in model-visible tool arguments."""

    def __init__(self, base_url: str, access_token: str, timeout_seconds: float = 30.0,
                 client: Optional[httpx.Client] = None):
        self._base_url = base_url.rstrip("/")
        self._access_token = access_token
        self._timeout_seconds = max(1.0, timeout_seconds)
        self._client = client

    def get(self, path: str, params: Optional[dict[str, Any]] = None) -> Any:
        return self._request("GET", path, params=params)

    def post(self, path: str, body: Optional[dict[str, Any]] = None) -> Any:
        return self._request("POST", path, json=body)

    def _request(self, method: str, path: str, **kwargs: Any) -> Any:
        if not self._access_token.strip():
            raise RuntimeError("MCP_ACCESS_TOKEN is required; do not pass a JWT as a tool argument")
        headers = {"Authorization": f"Bearer {self._access_token}"}
        if self._client is not None:
            response = self._client.request(method, path, headers=headers, **kwargs)
        else:
            with httpx.Client(base_url=self._base_url, timeout=self._timeout_seconds) as client:
                response = client.request(method, path, headers=headers, **kwargs)
        response.raise_for_status()
        payload = response.json()
        if not isinstance(payload, dict) or payload.get("code") != 200:
            message = payload.get("message") if isinstance(payload, dict) else "invalid backend response"
            raise RuntimeError(f"Backend tool request rejected: {message}")
        return payload.get("data")


def backend_client() -> BackendToolClient:
    return BackendToolClient(
        settings.mcp_backend_base_url,
        settings.mcp_access_token,
        settings.mcp_request_timeout_seconds,
    )


mcp = FastMCP(
    "Lab Resource Reservation",
    instructions=(
        "Authenticated lab reservation capabilities. The MCP process owns its backend credential; "
        "never ask a user to place a JWT in a tool argument. All permission checks execute in Java. "
        "Reservation mutations are intentionally not exposed."
    ),
    json_response=True,
)


@mcp.tool()
def get_my_reservation_context() -> dict[str, Any]:
    """Get the caller's own upcoming reservations and summary. No reservation is changed."""
    return backend_client().get("/knowledge/tools/reservation-context/me")


@mcp.tool()
def find_resource_availability(keyword: str = "", limit: int = 5) -> dict[str, Any]:
    """Find open future resource slots. The result is filtered by the platform's own access policy."""
    safe_limit = max(1, min(10, limit))
    return backend_client().get("/knowledge/tools/resource-availability", {
        "keyword": keyword.strip(), "limit": safe_limit,
    })


@mcp.tool()
def preview_reservation_cancellation(reservation_id: int) -> dict[str, Any]:
    """Check whether the caller may cancel one reservation. This is preview-only and never cancels it."""
    if reservation_id <= 0:
        raise ValueError("reservation_id must be positive")
    return backend_client().get(f"/knowledge/tools/cancellation-preview/{reservation_id}")


@mcp.tool()
def ask_lab_knowledge(question: str, session_id: str = "") -> dict[str, Any]:
    """Ask the platform's ACL-aware RAG/Agent service. This never mutates a reservation."""
    if not question.strip():
        raise ValueError("question must not be blank")
    body: dict[str, Any] = {"question": question.strip()}
    if session_id.strip():
        body["sessionId"] = session_id.strip()
    return backend_client().post("/knowledge/qa/ask", body)


def main() -> None:
    """Run a per-user sidecar; stdio is the default to avoid unauthenticated network exposure."""
    transport = os.getenv("MCP_TRANSPORT", "stdio").strip().lower()
    if transport not in {"stdio", "streamable-http"}:
        raise ValueError("MCP_TRANSPORT must be stdio or streamable-http")
    mcp.run(transport=transport)


if __name__ == "__main__":
    main()
