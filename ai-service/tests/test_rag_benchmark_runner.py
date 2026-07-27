from app.core import rag_pipeline


def _result(chunk_id: str, content: str, score: float) -> dict:
    return {
        "document_id": 9001,
        "chunk_id": chunk_id,
        "content": content,
        "content_hash": f"hash-{chunk_id}",
        "score": score,
    }


def test_retrieval_can_bypass_reranker_for_baseline_comparison(monkeypatch):
    monkeypatch.setattr(rag_pipeline.settings, "retrieval_mode", "keyword")
    monkeypatch.setattr(rag_pipeline.settings, "enable_hybrid_search", False)
    monkeypatch.setattr(rag_pipeline.reranker, "candidate_limit", lambda top_k: top_k)
    monkeypatch.setattr(
        rag_pipeline.vectorstore,
        "search_by_keywords",
        lambda _keywords, top_k, document_ids, document_versions=None: [
            _result("first", "第一条", 9.0),
            _result("second", "第二条", 8.0),
        ],
    )
    monkeypatch.setattr(
        rag_pipeline.reranker,
        "rerank",
        lambda *_args: (_ for _ in ()).throw(AssertionError("reranker must be bypassed")),
    )

    results = rag_pipeline.retrieve_candidates(
        "规则问题",
        document_ids=[9001],
        top_k=2,
        apply_rerank=False,
    )

    assert [item["chunk_id"] for item in results] == ["first", "second"]
