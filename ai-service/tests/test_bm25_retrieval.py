from app.core import vectorstore
from app.core.vectorstore import _bm25_tokens


def test_bm25_tokens_keep_rules_numbers_and_chinese_bigrams():
    tokens = _bm25_tokens("S42 自定义控制器不得设置广告位，预留 3m 电缆")

    assert "s42" in tokens
    assert "3m" in tokens
    assert "自定" in tokens
    assert "控制" in tokens
    assert "广告" in tokens
    assert "告位" in tokens


def test_search_by_keywords_preserves_a_complete_query_string(monkeypatch):
    captured = {}

    def fake_search(query, top_k, document_ids):
        captured.update(query=query, top_k=top_k, document_ids=document_ids)
        return []

    monkeypatch.setattr(vectorstore, "search_by_bm25", fake_search)

    vectorstore.search_by_keywords(
        "ACL-DIAG-LEXICAL-20260723 唯一诊断定位符",
        top_k=20,
        document_ids=[36],
    )

    assert captured == {
        "query": "ACL-DIAG-LEXICAL-20260723 唯一诊断定位符",
        "top_k": 20,
        "document_ids": [36],
    }


def test_search_by_keywords_still_accepts_keyword_sequences(monkeypatch):
    captured = {}

    def fake_search(query, top_k, document_ids):
        captured.update(query=query, top_k=top_k, document_ids=document_ids)
        return []

    monkeypatch.setattr(vectorstore, "search_by_bm25", fake_search)

    vectorstore.search_by_keywords(["取消预约", "违约处理"], top_k=5, document_ids=[3, 4])

    assert captured["query"] == "取消预约 违约处理"
