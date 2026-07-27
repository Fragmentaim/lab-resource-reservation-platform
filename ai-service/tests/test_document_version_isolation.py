import asyncio

from app.api import documents
from app.core.vectorstore import _document_filter


def test_qdrant_filter_pairs_each_document_with_its_active_version():
    query_filter = _document_filter(
        [12, 18],
        {12: "v1", 18: "v3"},
    )

    assert query_filter.model_dump(exclude_none=True) == {
        "should": [
            {
                "must": [
                    {"key": "document_id", "match": {"value": 12}},
                    {"key": "doc_version", "match": {"value": "v1"}},
                ]
            },
            {
                "must": [
                    {"key": "document_id", "match": {"value": 18}},
                    {"key": "doc_version", "match": {"value": "v3"}},
                ]
            },
        ]
    }


def test_version_cleanup_targets_both_indexes(monkeypatch):
    calls = []
    monkeypatch.setattr(
        documents.vectorstore,
        "delete_by_document_version",
        lambda document_id, doc_version: calls.append(
            ("qdrant", document_id, doc_version)
        ) or 4,
    )
    monkeypatch.setattr(
        documents.elasticsearch_store,
        "delete_by_document_version",
        lambda document_id, doc_version: calls.append(
            ("elasticsearch", document_id, doc_version)
        ) or 4,
    )

    response = asyncio.run(documents.delete_document_version(12, "v2"))

    assert response.deleted_count == 4
    assert calls == [
        ("qdrant", 12, "v2"),
        ("elasticsearch", 12, "v2"),
    ]
