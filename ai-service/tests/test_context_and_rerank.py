from app.core import reranker
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


def test_context_assembler_trims_evidence_and_keeps_complete_history_turns():
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
            "context_window_tokens": 900,
            "max_output_tokens": 100,
            "safety_margin_tokens": 100,
            "summary_max_tokens": 30,
        },
    )

    stats = result["context_stats"]
    assert stats["total_prompt_tokens"] <= stats["prompt_budget_tokens"]
    assert stats["selected_source_count"] >= 1
    assert stats["summary_truncated"] is True
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
