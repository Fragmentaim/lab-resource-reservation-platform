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


def _invoke_with(fake_client, captured=None):
    def invoke(_, __, operation):
        if captured is not None:
            original = fake_client.chat.completions.create

            def create(**kwargs):
                captured["messages"] = kwargs["messages"]
                return original(**kwargs)

            fake_client.chat.completions.create = create
        return operation(fake_client, "test-model"), {"model": "test-model"}
    return invoke


def test_tool_calling_parses_valid_and_invalid_model_arguments(monkeypatch):
    request = tool_calling.ToolCallingRoundRequest(
        question="查询预约制度",
        tools=[{"type": "function", "function": {"name": "knowledge_search"}}],
    )
    monkeypatch.setattr(tool_calling.model_gateway, "invoke", _invoke_with(_fake_tool_response('{"query":"预约制度"}')))
    response = asyncio.run(tool_calling.tool_calling_round(request))
    assert response.tool_calls[0].arguments == {"query": "预约制度"}

    monkeypatch.setattr(tool_calling.model_gateway, "invoke", _invoke_with(_fake_tool_response("not-json")))
    response = asyncio.run(tool_calling.tool_calling_round(request))
    assert response.tool_calls[0].arguments == {}


def test_tool_calling_preserves_assistant_tool_turn_boundaries(monkeypatch):
    captured = {}
    request = tool_calling.ToolCallingRoundRequest(
        question="查询制度",
        tools=[{"type": "function", "function": {"name": "knowledge_search"}}],
        executed_calls=[
            tool_calling.ExecutedToolCall(call_id="call-1", name="knowledge_search", round=1,
                                          arguments={"query": "制度"}, output={"status": "OK"}),
            tool_calling.ExecutedToolCall(call_id="call-2", name="knowledge_open_chunks", round=2,
                                          arguments={"chunkUids": ["candidate-1"]}, output={"status": "OK"}),
        ],
    )
    monkeypatch.setattr(tool_calling.model_gateway, "invoke",
                        _invoke_with(_fake_tool_response('{"query":"制度"}'), captured))

    asyncio.run(tool_calling.tool_calling_round(request))

    assistant_calls = [message for message in captured["messages"] if message["role"] == "assistant"]
    assert len(assistant_calls) == 2
    assert assistant_calls[0]["tool_calls"][0]["function"]["name"] == "knowledge_search"
    assert assistant_calls[1]["tool_calls"][0]["function"]["name"] == "knowledge_open_chunks"


def test_tool_calling_sse_emits_heartbeats_and_completion(monkeypatch):
    async def fake_round(_request):
        await asyncio.sleep(1.05)
        return tool_calling.ToolCallingRoundResponse(answer="完成", model="test-model")

    monkeypatch.setattr(tool_calling, "tool_calling_round", fake_round)
    request = tool_calling.ToolCallingRoundRequest(question="测试", tools=[{"type": "function"}])

    async def collect():
        response = await tool_calling.tool_calling_round_stream(request)
        return [chunk async for chunk in response.body_iterator]

    events = "".join(awaitable for awaitable in asyncio.run(collect()))
    assert "event: started" in events
    assert "event: heartbeat" in events
    assert "event: completed" in events


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
