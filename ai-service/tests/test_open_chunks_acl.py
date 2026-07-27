import asyncio

from app.api import qa
from app.models.schemas import KnowledgeOpenChunksRequest


def test_open_chunks_passes_java_acl_document_allow_list_to_vector_store(monkeypatch):
    captured = {}

    def fake_open(*, chunk_ids, document_ids, document_versions=None):
        captured["chunk_ids"] = chunk_ids
        captured["document_ids"] = document_ids
        captured["document_versions"] = document_versions
        return [{
            "chunk_id": "chunk-1",
            "document_id": 7,
            "content": "已授权的片段",
        }]

    monkeypatch.setattr(qa.vectorstore, "get_chunks_by_ids", fake_open)
    response = asyncio.run(qa.open_chunks(KnowledgeOpenChunksRequest(
        chunk_uids=["chunk-1", "chunk-not-authorized"],
        document_ids=[7],
        document_versions={7: "v2"},
    )))

    assert captured == {
        "chunk_ids": ["chunk-1", "chunk-not-authorized"],
        "document_ids": [7],
        "document_versions": {7: "v2"},
    }
    assert [chunk.chunk_uid for chunk in response.chunks] == ["chunk-1"]
