"""Small, dependency-free retrieval regression gate for curated knowledge cases."""

from dataclasses import dataclass
from typing import Callable, Iterable, Sequence


@dataclass(frozen=True)
class RetrievalCase:
    name: str
    question: str
    document_ids: tuple[int, ...]
    expected_chunk_ids: tuple[str, ...]


@dataclass(frozen=True)
class RetrievalEvaluation:
    case_count: int
    recall_at_k: float
    mrr_at_k: float
    acl_violations: tuple[str, ...]


def evaluate_cases(
    cases: Iterable[RetrievalCase],
    retrieve: Callable[[str, Sequence[int], int], Sequence[dict]],
    top_k: int = 5,
) -> RetrievalEvaluation:
    """Measure grounded recall/MRR and detect any retrieval result outside the supplied ACL."""
    normalized_cases = list(cases)
    if not normalized_cases:
        return RetrievalEvaluation(0, 0.0, 0.0, ())

    recalls: list[float] = []
    reciprocal_ranks: list[float] = []
    acl_violations: list[str] = []
    for case in normalized_cases:
        expected = set(case.expected_chunk_ids)
        results = list(retrieve(case.question, case.document_ids, top_k))[:top_k]
        returned_ids = [str(item.get("chunk_id") or "") for item in results]
        returned_document_ids = {item.get("document_id") for item in results}
        if not returned_document_ids.issubset(set(case.document_ids)):
            acl_violations.append(case.name)
        recalls.append(len(expected.intersection(returned_ids)) / len(expected) if expected else 1.0)
        first_rank = next((index for index, chunk_id in enumerate(returned_ids, start=1) if chunk_id in expected), None)
        reciprocal_ranks.append(0.0 if first_rank is None else 1.0 / first_rank)

    count = len(normalized_cases)
    return RetrievalEvaluation(
        case_count=count,
        recall_at_k=sum(recalls) / count,
        mrr_at_k=sum(reciprocal_ranks) / count,
        acl_violations=tuple(acl_violations),
    )


def assert_quality_gate(
    evaluation: RetrievalEvaluation,
    min_recall_at_k: float,
    min_mrr_at_k: float,
) -> None:
    """Fail fast in CI when curated retrieval quality or ACL isolation regresses."""
    if evaluation.acl_violations:
        raise AssertionError(f"retrieval returned chunks outside ACL for cases: {', '.join(evaluation.acl_violations)}")
    if evaluation.recall_at_k < min_recall_at_k:
        raise AssertionError(f"recall@k {evaluation.recall_at_k:.3f} < required {min_recall_at_k:.3f}")
    if evaluation.mrr_at_k < min_mrr_at_k:
        raise AssertionError(f"mrr@k {evaluation.mrr_at_k:.3f} < required {min_mrr_at_k:.3f}")
