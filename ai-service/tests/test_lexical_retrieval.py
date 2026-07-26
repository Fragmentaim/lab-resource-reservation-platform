from app.core import elasticsearch_store, vectorstore


def test_search_by_keywords_preserves_complete_query_and_acl(monkeypatch):
    captured = {}

    def fake_search(query, top_k, document_ids):
        captured.update(query=query, top_k=top_k, document_ids=document_ids)
        return [{"chunk_id": "es-result"}]

    monkeypatch.setattr(elasticsearch_store, "search", fake_search)

    results = vectorstore.search_by_keywords(
        "ACL-DIAG-LEXICAL-20260723 唯一诊断定位符",
        top_k=20,
        document_ids=[36],
    )

    assert results == [{"chunk_id": "es-result"}]
    assert captured == {
        "query": "ACL-DIAG-LEXICAL-20260723 唯一诊断定位符",
        "top_k": 20,
        "document_ids": [36],
    }


def test_search_by_keywords_accepts_keyword_sequences(monkeypatch):
    captured = {}

    def fake_search(query, top_k, document_ids):
        captured.update(query=query, top_k=top_k, document_ids=document_ids)
        return []

    monkeypatch.setattr(elasticsearch_store, "search", fake_search)

    vectorstore.search_by_keywords(
        ["取消预约", "违约处理"],
        top_k=5,
        document_ids=[3, 4],
    )

    assert captured == {
        "query": "取消预约 违约处理",
        "top_k": 5,
        "document_ids": [3, 4],
    }
