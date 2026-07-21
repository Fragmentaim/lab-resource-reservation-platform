import asyncio
from types import SimpleNamespace

from app.api import qa, tool_calling
from app.models.schemas import KnowledgeOpenChunksRequest


def _fake_tool_response(arguments: str):
    message = SimpleNamespace(
        content=None,
        tool_calls=[SimpleNamespace(
            id="call-1",
            function=SimpleNamespace(name="knowledge_search", arguments=arguments),
        )],
    )
    response = SimpleNamespace(choices=[SimpleNamespace(message=message)], usage=None)
    completions = SimpleNamespace(create=lambda **_: response)
    return SimpleNamespace(chat=SimpleNamespace(completions=completions))


def test_tool_calling_parses_valid_and_invalid_model_arguments(monkeypatch):
    request = tool_calling.ToolCallingRoundRequest(
        question="查询预约制度",
        tools=[{"type": "function", "function": {"name": "knowledge_search"}}],
    )
    monkeypatch.setattr(tool_calling, "get_client", lambda: _fake_tool_response('{"query":"预约制度"}'))
    response = asyncio.run(tool_calling.tool_calling_round(request))
    assert response.tool_calls[0].arguments == {"query": "预约制度"}

    monkeypatch.setattr(tool_calling, "get_client", lambda: _fake_tool_response("not-json"))
    response = asyncio.run(tool_calling.tool_calling_round(request))
    assert response.tool_calls[0].arguments == {}


def test_open_chunks_passes_java_acl_document_allow_list_to_vector_store(monkeypatch):
    captured = {}

    def fake_open(*, chunk_ids, document_ids):
        captured["chunk_ids"] = chunk_ids
        captured["document_ids"] = document_ids
        return [{
            "chunk_id": "chunk-1",
            "document_id": 7,
            "content": "已授权的片段",
        }]

    monkeypatch.setattr(qa.vectorstore, "get_chunks_by_ids", fake_open)
    response = asyncio.run(qa.open_chunks(KnowledgeOpenChunksRequest(
        chunk_uids=["chunk-1", "chunk-not-authorized"],
        document_ids=[7],
    )))

    assert captured == {"chunk_ids": ["chunk-1", "chunk-not-authorized"], "document_ids": [7]}
    assert [chunk.chunk_uid for chunk in response.chunks] == ["chunk-1"]
