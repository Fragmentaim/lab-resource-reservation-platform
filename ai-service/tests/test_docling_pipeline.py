from io import BytesIO
from types import SimpleNamespace

import pytest

from app.core.docling_pipeline import (
    _PICTURE_PROMPT,
    _block_type,
    _build_quality_report,
    _collect_page_numbers,
    _map_chunk,
    _prepare_source,
    _strip_reasoning,
)


class _FakeChunker:
    @staticmethod
    def contextualize(chunk):
        return f"{chunk.meta.headings[0]}\n{chunk.text}"


class _FakeTokenizer:
    @staticmethod
    def count_tokens(text):
        return len(text.split())


def test_docling_chunk_mapping_preserves_structure_and_provenance():
    chunk = SimpleNamespace(
        text="机器人进入兑换区。",
        meta=SimpleNamespace(
            headings=["4.2.7 兑换区"],
            model_dump=lambda mode: {
                "headings": ["4.2.7 兑换区"],
                "doc_items": [
                    {
                        "self_ref": "#/texts/2",
                        "label": "text",
                        "prov": [{"page_no": 55}],
                    },
                    {
                        "self_ref": "#/pictures/0",
                        "label": "picture",
                        "prov": [{"page_no": 55}],
                    },
                ],
                "origin": {"filename": "rules.pdf"},
            },
        ),
    )

    mapped = _map_chunk(
        chunk=chunk,
        chunk_index=3,
        chunker=_FakeChunker(),
        tokenizer=_FakeTokenizer(),
    )

    assert mapped["chunk_index"] == 3
    assert mapped["section_title"] == "4.2.7 兑换区"
    assert mapped["title_path"] == ["4.2.7 兑换区"]
    assert mapped["page_no"] == 55
    assert mapped["block_type"] == "PICTURE"
    assert mapped["chunk_strategy"] == "DOCLING_HYBRID"
    assert mapped["parser_provider"] == "docling"
    assert mapped["source_location"]["doc_item_refs"] == [
        "#/texts/2",
        "#/pictures/0",
    ]
    assert len(mapped["content_hash"]) == 64


def test_plain_text_is_routed_through_docling_markdown_backend(tmp_path):
    path = tmp_path / "notes.txt"
    path.write_text("# 规则\n不得进入禁区", encoding="utf-8")

    source = _prepare_source(path, "txt")

    assert source.name == "notes.md"
    assert isinstance(source.stream, BytesIO)
    assert (
        source.stream.getvalue().decode("utf-8").replace("\r\n", "\n")
        == "# 规则\n不得进入禁区"
    )


def test_unsupported_type_is_rejected_before_model_loading(tmp_path):
    path = tmp_path / "archive.rar"
    path.write_bytes(b"not-a-document")

    with pytest.raises(ValueError, match="Unsupported document type"):
        _prepare_source(path, "rar")


def test_nested_docling_provenance_pages_are_collected():
    metadata = {
        "doc_items": [
            {"prov": [{"page_no": 2}, {"page_no": 3}]},
            {"children": [{"prov": [{"page_no": 8}]}]},
        ]
    }

    assert _collect_page_numbers(metadata) == {2, 3, 8}


@pytest.mark.parametrize(
    ("labels", "expected"),
    [
        ({"table", "text"}, "TABLE"),
        ({"picture"}, "PICTURE"),
        ({"code"}, "CODE"),
        ({"section_header"}, "HEADING"),
        ({"list_item"}, "LIST"),
        ({"text"}, "TEXT"),
    ],
)
def test_block_type_uses_docling_labels(labels, expected):
    assert _block_type(labels) == expected


def test_picture_reasoning_is_not_indexed():
    value = "<think>internal reasoning</think>可检索的图片描述"

    assert _strip_reasoning(value) == "可检索的图片描述"


def test_picture_prompt_preserves_diagram_topology_for_retrieval():
    assert "图示摘要" in _PICTURE_PROMPT
    assert "节点" in _PICTURE_PROMPT
    assert "连线与条件" in _PICTURE_PROMPT
    assert "-->" in _PICTURE_PROMPT
    assert "不确定项" in _PICTURE_PROMPT
    assert "禁止补充" in _PICTURE_PROMPT


def test_quality_report_uses_real_docling_confidence_scores():
    page_scores = {
        1: SimpleNamespace(
            parse_score=0.9,
            layout_score=0.8,
            table_score=float("nan"),
            ocr_score=0.7,
        ),
        2: SimpleNamespace(
            parse_score=0.7,
            layout_score=0.6,
            table_score=0.5,
            ocr_score=float("nan"),
        ),
    }
    conversion = SimpleNamespace(
        status="success",
        confidence=SimpleNamespace(
            pages=page_scores,
            mean_score=0.76,
            low_score=0.52,
            parse_score=float("nan"),
            layout_score=float("nan"),
            table_score=float("nan"),
        ),
    )
    document = SimpleNamespace(
        export_to_markdown=lambda: "# 规则\n机器人不得进入禁区",
        texts=[],
        pages={1: object(), 2: object()},
        tables=[],
        pictures=[],
    )

    report = _build_quality_report(
        conversion,
        document,
        [
            {
                "source_location": {"pages": [1]},
            }
        ],
    )

    assert report["quality_score"] == pytest.approx(0.76)
    assert report["quality_low_score"] == pytest.approx(0.52)
    assert report["parse_average_confidence"] == pytest.approx(0.8)
    assert report["layout_average_confidence"] == pytest.approx(0.7)
    assert report["table_average_confidence"] == pytest.approx(0.5)
    assert report["ocr_average_confidence"] == pytest.approx(0.7)
    assert report["ocr_unit_count"] == 1
    assert report["scanned_unit_count"] is None
    assert report["ocr_character_count"] is None


def test_quality_report_does_not_invent_unavailable_confidence():
    conversion = SimpleNamespace(
        status="success",
        confidence=SimpleNamespace(
            pages={},
            mean_score=float("nan"),
            low_score=float("nan"),
            parse_score=float("nan"),
            layout_score=float("nan"),
            table_score=float("nan"),
        ),
    )
    document = SimpleNamespace(
        export_to_markdown=lambda: "纯文本内容",
        texts=[],
        pages={},
        tables=[],
        pictures=[],
    )

    report = _build_quality_report(conversion, document, [])

    assert report["quality_score"] is None
    assert report["quality_low_score"] is None
    assert report["ocr_unit_count"] is None
    assert report["ocr_average_confidence"] is None
