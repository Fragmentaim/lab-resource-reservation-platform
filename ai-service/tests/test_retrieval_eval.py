import pytest

from app.core.retrieval_eval import RetrievalCase, assert_quality_gate, evaluate_cases


def test_retrieval_evaluation_calculates_recall_mrr_and_acl_gate():
    cases = [
        RetrievalCase("cancel-rule", "取消预约规则", (12,), ("chunk-12-3",)),
        RetrievalCase("device-rule", "设备使用规则", (20, 21), ("chunk-21-1", "chunk-20-2")),
    ]

    results_by_question = {
        "取消预约规则": [{"chunk_id": "chunk-12-3", "document_id": 12}],
        "设备使用规则": [
            {"chunk_id": "chunk-21-1", "document_id": 21},
            {"chunk_id": "unrelated", "document_id": 20},
        ],
    }
    evaluation = evaluate_cases(cases, lambda question, _ids, _k: results_by_question[question])

    assert evaluation.case_count == 2
    assert evaluation.recall_at_k == 0.75
    assert evaluation.mrr_at_k == 1.0
    assert_quality_gate(evaluation, min_recall_at_k=0.7, min_mrr_at_k=0.9)


def test_retrieval_evaluation_rejects_acl_leaks_even_when_recall_is_high():
    case = RetrievalCase("private-rule", "私有规则", (12,), ("chunk-12-3",))
    evaluation = evaluate_cases(
        [case],
        lambda *_: [{"chunk_id": "chunk-12-3", "document_id": 99}],
    )

    with pytest.raises(AssertionError, match="outside ACL"):
        assert_quality_gate(evaluation, min_recall_at_k=1.0, min_mrr_at_k=1.0)
