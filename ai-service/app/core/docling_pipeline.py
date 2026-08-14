"""Unified Docling document ingestion and structure-aware chunking."""

from __future__ import annotations

from dataclasses import dataclass
from hashlib import sha256
from importlib.metadata import version
from io import BytesIO
from math import isfinite
from pathlib import Path
from threading import RLock
from typing import Any

from app.config import settings


_pipeline_lock = RLock()
_pipeline: "DoclingPipeline | None" = None

_PICTURE_PROMPT = """你是技术文档图像转写器。请只根据图片中可见的信息，
生成可直接进入知识库检索的中文 Markdown，不要回答文档之外的问题。

如果图片是流程图、状态图、时序图或系统架构图，请使用以下结构：
### 图示摘要
用简短自然语言说明整张图的目的、起点、终点和主要流程。
### 节点
- `N1`｜原始标签｜节点作用
### 连线与条件
- `N1` --[可见条件或关系]--> `N2`
### 输入与输出
- 输入：图片中明确出现的输入；输出：图片中明确出现的输出。
### 不确定项
- 记录无法辨认的文字、箭头方向或连接关系；没有则写“无”。

如果不是上述关系图，请使用“图示摘要、可见事实、不确定项”三个章节描述。
必须保留标题、标签、编号、数字、尺寸和单位。节点编号只用于表达关系，节点
名称必须保留图片原文。禁止补充图片中不存在的节点、箭头、条件或结论。"""


@dataclass(frozen=True)
class DocumentProcessingResult:
    chunks: list[dict[str, Any]]
    quality: dict[str, Any]

    @property
    def is_empty(self) -> bool:
        return not self.chunks


class DoclingPipeline:
    """Lazy, reusable Docling converter plus HybridChunker."""

    def __init__(self) -> None:
        (
            self._converter,
            self._chunker,
            self._tokenizer,
        ) = _build_docling_runtime()

    def process_file(
        self,
        file_path: str | Path,
        file_type: str,
    ) -> DocumentProcessingResult:
        source = _prepare_source(Path(file_path), file_type)
        with _pipeline_lock:
            conversion = self._converter.convert(source)
            document = conversion.document
            chunks = [
                _map_chunk(
                    chunk=chunk,
                    chunk_index=index,
                    chunker=self._chunker,
                    tokenizer=self._tokenizer,
                )
                for index, chunk in enumerate(self._chunker.chunk(document))
            ]

        return DocumentProcessingResult(
            chunks=chunks,
            quality=_build_quality_report(conversion, document, chunks),
        )


def process_file(
    file_path: str | Path,
    file_type: str,
) -> DocumentProcessingResult:
    """Parse and chunk one document with the shared Docling runtime."""
    global _pipeline
    if _pipeline is None:
        with _pipeline_lock:
            if _pipeline is None:
                _pipeline = DoclingPipeline()
    return _pipeline.process_file(file_path, file_type)


def _build_docling_runtime():
    # Heavy libraries are imported only when a document is actually processed.
    # The Agent/RAG request path therefore does not load OCR/layout models.
    from docling.chunking import HybridChunker
    from docling.datamodel.accelerator_options import (
        AcceleratorDevice,
        AcceleratorOptions,
    )
    from docling.datamodel.base_models import InputFormat
    from docling.datamodel.pipeline_options import (
        PdfPipelineOptions,
        PictureDescriptionApiOptions,
        PictureDescriptionVlmOptions,
    )
    from docling.document_converter import (
        DocumentConverter,
        ImageFormatOption,
        PdfFormatOption,
    )
    from docling_core.transforms.chunker.tokenizer.huggingface import (
        HuggingFaceTokenizer,
    )
    from transformers import AutoTokenizer

    options = PdfPipelineOptions()
    options.do_ocr = settings.docling_do_ocr
    options.do_table_structure = settings.docling_do_table_structure
    options.do_formula_enrichment = settings.docling_do_formula_enrichment
    options.do_picture_classification = settings.docling_do_picture_classification
    options.images_scale = settings.docling_images_scale
    options.accelerator_options = AcceleratorOptions(
        device=AcceleratorDevice(settings.docling_device.lower()),
        num_threads=max(1, settings.docling_num_threads),
    )

    if settings.docling_do_picture_classification:
        # torch.compile currently depends on Triton, which is unavailable in
        # the supported Windows runtime. Classification itself still runs.
        options.picture_classification_options.engine_options.compile_model = False

    picture_mode = settings.docling_picture_description_mode.strip().lower()
    if picture_mode not in {"off", "local", "api"}:
        raise ValueError(
            "DOCLING_PICTURE_DESCRIPTION_MODE must be off, local, or api"
        )
    if picture_mode != "off":
        options.do_picture_description = True
        options.generate_page_images = True
        options.generate_picture_images = True
        if picture_mode == "api":
            if not (
                settings.docling_picture_api_url
                and settings.docling_picture_api_key
                and settings.docling_picture_model
            ):
                raise ValueError(
                    "Docling API picture description requires URL, API key, and model"
                )
            options.enable_remote_services = True
            options.picture_description_options = PictureDescriptionApiOptions(
                url=settings.docling_picture_api_url,
                headers={
                    "Authorization": (
                        f"Bearer {settings.docling_picture_api_key}"
                    )
                },
                params={
                    "model": settings.docling_picture_model,
                    "max_tokens": settings.docling_picture_max_tokens,
                },
                prompt=_PICTURE_PROMPT,
                picture_area_threshold=0.0,
                scale=settings.docling_images_scale,
                batch_size=1,
                concurrency=1,
                timeout=settings.docling_picture_timeout_seconds,
            )
        else:
            options.picture_description_options = PictureDescriptionVlmOptions(
                repo_id=settings.docling_picture_local_model,
                prompt=_PICTURE_PROMPT,
                picture_area_threshold=0.0,
                scale=settings.docling_images_scale,
                batch_size=1,
                generation_config={
                    "max_new_tokens": settings.docling_picture_max_tokens,
                    "do_sample": False,
                },
            )

    converter = DocumentConverter(
        format_options={
            InputFormat.PDF: PdfFormatOption(pipeline_options=options),
            InputFormat.IMAGE: ImageFormatOption(pipeline_options=options),
        }
    )
    hf_tokenizer = AutoTokenizer.from_pretrained(
        settings.local_embedding_model,
        cache_dir=settings.hf_hub_cache or None,
    )
    tokenizer = HuggingFaceTokenizer(
        tokenizer=hf_tokenizer,
        max_tokens=settings.chunk_size,
    )
    chunker = HybridChunker(
        tokenizer=tokenizer,
        merge_peers=True,
        repeat_table_header=True,
    )
    return converter, chunker, tokenizer


def _prepare_source(path: Path, file_type: str):
    normalized = file_type.strip().lower().lstrip(".")
    supported = {
        "pdf",
        "doc",
        "docx",
        "ppt",
        "pptx",
        "xls",
        "xlsx",
        "csv",
        "md",
        "markdown",
        "html",
        "htm",
        "odt",
        "ods",
        "odp",
        "epub",
        "png",
        "jpg",
        "jpeg",
        "bmp",
        "tif",
        "tiff",
        "webp",
    }
    if normalized == "txt":
        from docling.datamodel.base_models import DocumentStream

        # Docling has no plain-text input format. Plain text is valid Markdown,
        # so route it through the Markdown backend without custom parsing.
        return DocumentStream(
            name=f"{path.stem}.md",
            stream=BytesIO(path.read_bytes()),
        )
    if normalized not in supported:
        raise ValueError(f"Unsupported document type for Docling: {file_type}")
    return path


def _map_chunk(
    *,
    chunk: Any,
    chunk_index: int,
    chunker: Any,
    tokenizer: Any,
) -> dict[str, Any]:
    content = _strip_reasoning(chunker.contextualize(chunk)).strip()
    metadata = _jsonable(chunk.meta)
    headings = [
        str(item).strip()
        for item in metadata.get("headings", [])
        if str(item).strip()
    ]
    doc_items = metadata.get("doc_items") or []
    pages = sorted(_collect_page_numbers(doc_items))
    labels = {
        str(item.get("label", "")).lower()
        for item in doc_items
        if isinstance(item, dict)
    }
    block_type = _block_type(labels)
    refs = [
        item.get("self_ref")
        for item in doc_items
        if isinstance(item, dict) and item.get("self_ref")
    ]
    return {
        "chunk_index": chunk_index,
        "content": content,
        "token_count": tokenizer.count_tokens(content),
        "page_no": pages[0] if pages else None,
        "section_title": headings[-1] if headings else None,
        "title_path": headings,
        "title_path_text": " > ".join(headings),
        "content_hash": sha256(
            " ".join(content.split()).encode("utf-8")
        ).hexdigest(),
        "char_start": None,
        "char_end": None,
        "block_type": block_type,
        "chunk_strategy": "DOCLING_HYBRID",
        "source_location": {
            "pages": pages,
            "doc_item_refs": refs,
            "origin": metadata.get("origin"),
        },
        "parser_provider": "docling",
    }


def _block_type(labels: set[str]) -> str:
    if "table" in labels:
        return "TABLE"
    if "picture" in labels:
        return "PICTURE"
    if "code" in labels:
        return "CODE"
    if labels.intersection({"section_header", "title"}):
        return "HEADING"
    if "list_item" in labels:
        return "LIST"
    return "TEXT"


def _collect_page_numbers(value: Any) -> set[int]:
    pages: set[int] = set()
    if isinstance(value, dict):
        page_no = value.get("page_no")
        if isinstance(page_no, int):
            pages.add(page_no)
        for child in value.values():
            pages.update(_collect_page_numbers(child))
    elif isinstance(value, list):
        for child in value:
            pages.update(_collect_page_numbers(child))
    return pages


def _build_quality_report(
    conversion: Any,
    document: Any,
    chunks: list[dict[str, Any]],
) -> dict[str, Any]:
    markdown = document.export_to_markdown()
    text_items = list(getattr(document, "texts", []) or [])
    pages = getattr(document, "pages", {}) or {}
    tables = list(getattr(document, "tables", []) or [])
    pictures = list(getattr(document, "pictures", []) or [])
    headings = [
        item
        for item in text_items
        if str(getattr(item, "label", "")).lower()
        in {"section_header", "title"}
    ]
    warnings: list[str] = []
    if not markdown.strip():
        warnings.append("DOCLING_EMPTY_DOCUMENT")
    status = str(getattr(conversion, "status", "unknown"))
    if "success" not in status.lower():
        warnings.append(f"DOCLING_STATUS_{status.upper()}")

    unit_count = len(pages) or 1
    non_empty_units = len(
        {
            page
            for chunk in chunks
            for page in (chunk.get("source_location") or {}).get("pages", [])
        }
    )
    if not pages and markdown.strip():
        non_empty_units = 1

    confidence = getattr(conversion, "confidence", None)
    page_confidences = getattr(confidence, "pages", {}) or {}
    parse_scores = _page_confidence_scores(page_confidences, "parse_score")
    layout_scores = _page_confidence_scores(page_confidences, "layout_score")
    table_scores = _page_confidence_scores(page_confidences, "table_score")
    ocr_scores = _page_confidence_scores(page_confidences, "ocr_score")
    quality_score = _finite_score(getattr(confidence, "mean_score", None))
    quality_low_score = _finite_score(getattr(confidence, "low_score", None))

    return {
        "provider": "docling",
        "provider_version": version("docling"),
        "parse_mode": "DOCLING_STRUCTURED",
        "unit_count": unit_count,
        "non_empty_unit_count": non_empty_units,
        "character_count": len(markdown),
        "heading_count": len(headings),
        "table_count": len(tables),
        "image_count": len(pictures),
        # Docling exposes OCR confidence but does not provide a reliable
        # "scanned page" flag or a ready-made OCR character count.
        "scanned_unit_count": None,
        "ocr_unit_count": len(ocr_scores) if page_confidences else None,
        "ocr_character_count": None,
        "ocr_average_confidence": _average(ocr_scores),
        "parse_average_confidence": _average_or_aggregate(
            parse_scores, confidence, "parse_score"
        ),
        "layout_average_confidence": _average_or_aggregate(
            layout_scores, confidence, "layout_score"
        ),
        "table_average_confidence": _average_or_aggregate(
            table_scores, confidence, "table_score"
        ),
        "empty_unit_count": max(0, unit_count - non_empty_units),
        "quality_score": quality_score,
        "quality_low_score": quality_low_score,
        "warnings": warnings,
        "decisions": [
            {
                "stage": "document_conversion",
                "provider": "docling",
                "status": status,
                "ocr": settings.docling_do_ocr,
                "table_structure": settings.docling_do_table_structure,
                "formula_enrichment": settings.docling_do_formula_enrichment,
                "picture_classification": (
                    settings.docling_do_picture_classification
                ),
                "picture_description": (
                    settings.docling_picture_description_mode
                ),
                "confidence": {
                    "mean_score": quality_score,
                    "low_score": quality_low_score,
                    "page_score_count": len(page_confidences),
                    "ocr_page_score_count": len(ocr_scores),
                },
            },
            {
                "stage": "chunking",
                "provider": "docling.HybridChunker",
                "max_tokens": settings.chunk_size,
                "chunk_count": len(chunks),
            },
        ],
    }


def _page_confidence_scores(
    page_confidences: Any,
    field: str,
) -> list[float]:
    values = (
        page_confidences.values()
        if isinstance(page_confidences, dict)
        else page_confidences
    )
    scores: list[float] = []
    for item in values:
        score = _finite_score(getattr(item, field, None))
        if score is not None:
            scores.append(score)
    return scores


def _average_or_aggregate(
    scores: list[float],
    confidence: Any,
    field: str,
) -> float | None:
    average = _average(scores)
    if average is not None:
        return average
    return _finite_score(getattr(confidence, field, None))


def _average(scores: list[float]) -> float | None:
    if not scores:
        return None
    return sum(scores) / len(scores)


def _finite_score(value: Any) -> float | None:
    try:
        score = float(value)
    except (TypeError, ValueError):
        return None
    return score if isfinite(score) else None


def _jsonable(value: Any) -> Any:
    if isinstance(value, dict):
        return {key: _jsonable(item) for key, item in value.items()}
    if isinstance(value, (list, tuple)):
        return [_jsonable(item) for item in value]
    if hasattr(value, "export_json_dict"):
        return _jsonable(value.export_json_dict())
    if hasattr(value, "model_dump"):
        return _jsonable(value.model_dump(mode="json"))
    return value


def _strip_reasoning(text: str) -> str:
    if "<think>" in text and "</think>" in text:
        return text.split("</think>", 1)[1].strip()
    return text
