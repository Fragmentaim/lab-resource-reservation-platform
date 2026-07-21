"""Stable, parser-neutral intermediate representation for knowledge ingestion.

Providers only describe what they can reliably extract.  Chunking, embedding and
indexing consume this IR instead of knowing about PDF/DOCX/XLSX internals.
"""

from __future__ import annotations

from dataclasses import asdict, dataclass, field
from typing import Any, Literal


BlockType = Literal["TITLE", "TEXT", "TABLE", "IMAGE"]


@dataclass(frozen=True)
class SourceLocation:
    unit_no: int
    unit_label: str
    char_start: int | None = None
    char_end: int | None = None

    def as_dict(self) -> dict[str, Any]:
        return asdict(self)


@dataclass(frozen=True)
class DocumentBlock:
    block_type: BlockType
    content: str
    location: SourceLocation
    title_path: list[str] = field(default_factory=list)
    metadata: dict[str, Any] = field(default_factory=dict)


@dataclass
class ParseQualityReport:
    provider: str
    provider_version: str
    parse_mode: str
    unit_count: int
    non_empty_unit_count: int
    character_count: int
    heading_count: int = 0
    table_count: int = 0
    image_count: int = 0
    scanned_unit_count: int = 0
    ocr_unit_count: int = 0
    ocr_character_count: int = 0
    ocr_average_confidence: float | None = None
    empty_unit_count: int = 0
    quality_score: float = 1.0
    warnings: list[str] = field(default_factory=list)
    decisions: list[dict[str, Any]] = field(default_factory=list)

    def as_dict(self) -> dict[str, Any]:
        return asdict(self)


@dataclass
class ParsedDocument:
    blocks: list[DocumentBlock]
    quality: ParseQualityReport

    @property
    def is_empty(self) -> bool:
        return not any(block.content.strip() for block in self.blocks)
