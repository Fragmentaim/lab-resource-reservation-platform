from app.core import rag_pipeline, reranker
from app.core.context_assembler import assemble_context


def _result(chunk_id: str, content: str, score: float = 0.8) -> dict:
    return {
        "document_id": 1,
        "chunk_id": chunk_id,
        "page_no": 2,
        "section_title": "预约规则",
        "content": content,
        "score": score,
    }


def test_context_assembler_keeps_summary_and_complete_history_turns():
    result = assemble_context(
        original_question="我能预约什么时段？",
        rewritten_question="",
        ranked_results=[
            _result("chunk-a", "预约规则" * 220),
            _result("chunk-b", "备用证据" * 220, 0.7),
        ],
        session_summary="旧摘要" * 100,
        chat_history=[
            {"role": "user", "content": "第一轮问题" * 20},
            {"role": "assistant", "content": "第一轮回答" * 20},
            {"role": "user", "content": "第二轮问题" * 20},
            {"role": "assistant", "content": "第二轮回答" * 20},
        ],
        top_k=2,
        context_options={
            "context_window_tokens": 1_500,
            "max_output_tokens": 100,
            "safety_margin_tokens": 100,
        },
    )

    stats = result["context_stats"]
    assert stats["total_prompt_tokens"] <= stats["prompt_budget_tokens"]
    assert stats["selected_source_count"] >= 1
    assert stats["summary_truncated"] is False
    for index, message in enumerate(result["history"]):
        if message["role"] == "assistant":
            assert index > 0
            assert result["history"][index - 1]["role"] == "user"


def test_rerank_falls_back_to_local_scoring_when_api_returns_nothing(monkeypatch):
    monkeypatch.setattr(reranker.settings, "enable_rerank", True)
    monkeypatch.setattr(reranker, "_rerank_by_api", lambda *_: [])
    results = [
        _result("general", "实验室开放时间", 0.9),
        _result("policy", "预约取消需要提前确认预约规则", 0.4),
    ]

    ranked = reranker.rerank("取消预约规则", results, ["取消", "预约"], top_k=2)

    assert [item["rerank_provider"] for item in ranked] == ["local", "local"]
    assert ranked[0]["chunk_id"] == "policy"
    assert all(item["retrieval_score"] is not None for item in ranked)


def test_rerank_uses_local_cross_encoder_when_enabled(monkeypatch):
    class FakeCrossEncoder:
        def predict(self, pairs, **_kwargs):
            assert pairs[0][0] == "取消预约规则"
            return [0.2, 0.9]

    monkeypatch.setattr(reranker.settings, "enable_rerank", True)
    monkeypatch.setattr(reranker.settings, "use_local_reranker", True)
    monkeypatch.setattr(reranker, "_rerank_by_api", lambda *_: [])
    monkeypatch.setattr(reranker, "_get_local_cross_encoder", lambda: FakeCrossEncoder())
    results = [_result("general", "实验室开放时间", 0.9), _result("policy", "预约取消规则", 0.4)]

    ranked = reranker.rerank("取消预约规则", results, ["取消", "预约"], top_k=2)

    assert [item["chunk_id"] for item in ranked] == ["policy", "general"]
    assert [item["rerank_provider"] for item in ranked] == ["local_cross_encoder", "local_cross_encoder"]


def test_hybrid_retrieval_uses_rrf_to_fuse_duplicate_candidates_and_preserves_acl(monkeypatch):
    monkeypatch.setattr(rag_pipeline.settings, "retrieval_mode", "vector")
    monkeypatch.setattr(rag_pipeline.settings, "enable_embedding", True)
    monkeypatch.setattr(rag_pipeline.settings, "enable_hybrid_search", True)
    monkeypatch.setattr(rag_pipeline.settings, "hybrid_rrf_k", 60)
    monkeypatch.setattr(rag_pipeline.reranker, "candidate_limit", lambda top_k: top_k)
    monkeypatch.setattr(rag_pipeline.reranker, "rerank", lambda _question, results, _keywords, _limit: results)
    monkeypatch.setattr(rag_pipeline.embedder, "embed_query", lambda _question: [0.1, 0.2])

    calls = []

    def vector_search(**kwargs):
        calls.append(("vector", kwargs["document_ids"]))
        return [
            _result("vector-only", "实验室开放时间", 0.95),
            _result("shared", "取消预约需要提前确认", 0.75),
        ]

    def keyword_search(_keywords, top_k, document_ids, document_versions=None):
        calls.append(("keyword", document_ids))
        return [
            _result("shared", "取消预约需要提前确认", 9.0),
            _result("keyword-only", "预约规则与违约处理", 6.0),
        ]

    monkeypatch.setattr(rag_pipeline.vectorstore, "search", vector_search)
    monkeypatch.setattr(rag_pipeline.vectorstore, "search_by_keywords", keyword_search)

    results = rag_pipeline.retrieve_candidates("取消预约规则", document_ids=[12], top_k=3)

    assert [item["chunk_id"] for item in results] == ["shared", "vector-only", "keyword-only"]
    assert results[0]["retrieval_source"] == "bm25,vector"
    assert results[0]["fusion_method"] == "weighted_rrf"
    assert results[0]["retrieval_ranks"] == {"vector": 2, "bm25": 1}
    assert len({item["chunk_id"] for item in results}) == 3
    assert calls == [("vector", [12]), ("keyword", [12])]


def test_retrieval_uses_large_candidate_pool_but_returns_requested_top_k(monkeypatch):
    monkeypatch.setattr(rag_pipeline.settings, "retrieval_mode", "keyword")
    monkeypatch.setattr(rag_pipeline.settings, "enable_hybrid_search", False)
    monkeypatch.setattr(rag_pipeline.settings, "enable_embedding", False)
    monkeypatch.setattr(rag_pipeline.reranker, "candidate_limit", lambda _top_k: 20)

    candidates = [
        _result(f"chunk-{index}", f"候选证据 {index}", 20 - index)
        for index in range(20)
    ]
    monkeypatch.setattr(
        rag_pipeline.vectorstore,
        "search_by_keywords",
        lambda *_args, **_kwargs: candidates,
    )

    captured = {}

    def fake_rerank(_question, results, _keywords, top_k):
        captured["candidate_count"] = len(results)
        captured["top_k"] = top_k
        return results[:top_k]

    monkeypatch.setattr(rag_pipeline.reranker, "rerank", fake_rerank)

    results = rag_pipeline.retrieve_candidates("预约规则", document_ids=[12], top_k=5)

    assert captured == {"candidate_count": 20, "top_k": 5}
    assert len(results) == 5
