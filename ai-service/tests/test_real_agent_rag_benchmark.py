from evals.run_real_agent_rag_benchmark import extract_answer_letter


def test_extract_answer_letter_normalizes_markdown() -> None:
    assert extract_answer_letter("正确选项：**D**") == "D"
    assert extract_answer_letter("## 答案：**B. ①③⑤**") == "B"


def test_extract_answer_letter_rejects_multiple_choices() -> None:
    assert extract_answer_letter("正确答案：A、D") is None
