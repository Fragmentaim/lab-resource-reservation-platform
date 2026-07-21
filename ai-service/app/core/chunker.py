import hashlib
import re
from typing import List, Tuple
from app.config import settings
from app.core.document_ir import DocumentBlock, ParsedDocument


_HEADING_RE = re.compile(r"^(#{1,6})\s+(.+?)\s*$")
_NUMBERED_HEADING_RE = re.compile(
    r"^((第[一二三四五六七八九十百千0-9]+[章节条])|([一二三四五六七八九十]+[、.])|(\d+(\.\d+){0,4}[、.\s]))\s*(.+)$"
)
_FENCED_CODE_RE = re.compile(r"^```")
_CODE_START_RE = re.compile(
    r"^(?:typedef\b|struct\b|class\b|enum\b|#include\b|using\b|"
    r"(?:public|private|protected)\s*:|[A-Za-z_]\w*(?:\s*[<>,:*&\[\]]+\s*|\s+)"
    r"[A-Za-z_]\w*\s*\([^)]*\)\s*\{)"
)
_CODE_LINE_RE = re.compile(r"^(?:[{}]|.*[;{}]$|\s+[A-Za-z_]\w*.*)$")
_LIST_ITEM_RE = re.compile(r"^(?:[-*+]\s+|\d+[.)、]\s+|[一二三四五六七八九十]+[、.])")
_ATOMIC_BLOCK_TYPES = {"TABLE", "CODE", "FIGURE", "IMAGE"}


def chunk_document(document: ParsedDocument) -> List[dict]:
    """Split parser-neutral IR blocks into retrievable chunks."""
    from langchain_text_splitters import RecursiveCharacterTextSplitter

    splitter = RecursiveCharacterTextSplitter(
        chunk_size=settings.chunk_size,
        chunk_overlap=settings.chunk_overlap,
        separators=["\n\n", "\n", "。", "！", "？", ".", "!", "?", " ", ""],
        length_function=len,
    )

    chunks = []
    chunk_index = 0
    for block in document.blocks:
        if not block.content.strip() or block.block_type == "TITLE":
            continue

        if block.block_type in _ATOMIC_BLOCK_TYPES:
            chunk_index = _append_chunk(
                chunks=chunks,
                chunk_index=chunk_index,
                content=block.content.strip(),
                block=block,
                title_path=block.title_path,
                char_start=block.location.char_start or 0,
                char_end=block.location.char_end,
                parser_provider=document.quality.provider,
                chunk_strategy="ATOMIC",
            )
            continue

        for section in _extract_sections(block.content, block.title_path):
            section_type = section["block_type"]
            section_content = section["content"]
            if section_type in _ATOMIC_BLOCK_TYPES:
                chunk_index = _append_chunk(
                    chunks=chunks,
                    chunk_index=chunk_index,
                    content=section_content,
                    block=block,
                    title_path=section["title_path"],
                    char_start=section["char_start"],
                    char_end=section["char_start"] + len(section_content),
                    parser_provider=document.quality.provider,
                    chunk_strategy="ATOMIC",
                    block_type=section_type,
                )
                continue

            cursor = 0
            for part in splitter.split_text(section_content):
                part = part.strip()
                if not part:
                    continue
                char_start = section_content.find(part, cursor)
                if char_start < 0:
                    char_start = cursor
                cursor = char_start + len(part)
                chunk_index = _append_chunk(
                    chunks=chunks,
                    chunk_index=chunk_index,
                    content=part,
                    block=block,
                    title_path=section["title_path"],
                    char_start=section["char_start"] + char_start,
                    char_end=section["char_start"] + cursor,
                    parser_provider=document.quality.provider,
                    chunk_strategy="SEMANTIC_SPLIT",
                    block_type=section_type,
                )

    return chunks


def chunk_text(pages: List[Tuple[str, int]]) -> List[dict]:
    """Backward-compatible adapter for legacy callers."""
    from app.core.document_ir import (
        ParseQualityReport,
        ParsedDocument,
        SourceLocation,
    )
    blocks = [
        DocumentBlock("TEXT", text, SourceLocation(page_no, f"page:{page_no}"))
        for text, page_no in pages
    ]
    return chunk_document(ParsedDocument(
        blocks=blocks,
        quality=ParseQualityReport(
            provider="legacy-pages",
            provider_version="1",
            parse_mode="LEGACY",
            unit_count=len(pages),
            non_empty_unit_count=len(blocks),
            character_count=sum(len(text) for text, _ in pages),
        ),
    ))


def _append_chunk(
    *,
    chunks: list[dict],
    chunk_index: int,
    content: str,
    block: DocumentBlock,
    title_path: List[str],
    char_start: int,
    char_end: int | None,
    parser_provider: str,
    chunk_strategy: str,
    block_type: str | None = None,
) -> int:
    content_with_context = _with_heading_context(content, title_path)
    chunks.append({
        "chunk_index": chunk_index,
        "content": content_with_context,
        "page_no": block.location.unit_no,
        "section_title": title_path[-1] if title_path else None,
        "title_path": list(title_path),
        "title_path_text": " > ".join(title_path),
        "content_hash": _content_hash(content_with_context),
        "char_start": char_start,
        "char_end": char_end if char_end is not None else char_start + len(content),
        "token_count": _estimate_token_count(content_with_context),
        "block_type": block_type or block.block_type,
        "chunk_strategy": chunk_strategy,
        "source_location": block.location.as_dict(),
        "parser_provider": parser_provider,
    })
    return chunk_index + 1


def _extract_sections(text: str, initial_title_path: List[str] | None = None) -> List[dict]:
    """Extract heading-aware text sections from one parsed page."""
    sections = []
    title_stack: List[tuple[int, str]] = [
        (index + 1, title) for index, title in enumerate(initial_title_path or [])
    ]
    current_lines: List[str] = []
    current_start = 0
    current_path: List[str] = [title for _, title in title_stack]
    offset = 0

    for raw_line in text.splitlines():
        line = raw_line.strip()
        line_start = offset
        offset += len(raw_line) + 1

        heading = _parse_heading(line)
        if heading:
            if current_lines:
                sections.extend(_build_semantic_sections(current_lines, current_path, current_start))
                current_lines = []

            level, title = heading
            title_stack = [(lvl, txt) for lvl, txt in title_stack if lvl < level]
            title_stack.append((level, title))
            current_path = [txt for _, txt in title_stack]
            current_start = line_start + len(raw_line) + 1
            continue

        if not current_lines and line:
            current_start = line_start
        current_lines.append(raw_line)

    if current_lines:
        sections.extend(_build_semantic_sections(current_lines, current_path, current_start))

    if sections:
        return [s for s in sections if s["content"]]

    content = text.strip()
    return [{
        "content": content,
        "title_path": [],
        "char_start": 0,
        "block_type": "TEXT",
    }] if content else []


def _build_semantic_sections(lines: List[str], title_path: List[str], char_start: int) -> list[dict]:
    """Classify code/list runs without relying on source file names or page numbers."""
    sections: list[dict] = []
    pending: list[str] = []
    pending_start = char_start
    mode = "TEXT"
    offset = char_start

    def flush() -> None:
        nonlocal pending
        content = "\n".join(pending).strip()
        if content:
            sections.append({
                "content": content,
                "title_path": list(title_path),
                "char_start": pending_start,
                "block_type": mode,
            })
        pending = []

    for raw_line in lines:
        line = raw_line.strip()
        line_mode = _line_mode(line, mode)
        if pending and line_mode != mode:
            flush()
            pending_start = offset
        if not pending:
            pending_start = offset
            mode = line_mode
        pending.append(raw_line)
        offset += len(raw_line) + 1

    flush()
    return sections


def _line_mode(line: str, current_mode: str) -> str:
    if not line:
        return current_mode
    if _FENCED_CODE_RE.match(line):
        return "CODE"
    if _CODE_START_RE.match(line):
        return "CODE"
    if current_mode == "CODE" and _CODE_LINE_RE.match(line):
        return "CODE"
    if _LIST_ITEM_RE.match(line):
        return "LIST"
    return "TEXT"


def _parse_heading(line: str) -> tuple[int, str] | None:
    if not line:
        return None

    markdown_heading = _HEADING_RE.match(line)
    if markdown_heading:
        return len(markdown_heading.group(1)), markdown_heading.group(2).strip()

    numbered_heading = _NUMBERED_HEADING_RE.match(line)
    if numbered_heading and len(line) <= 80:
        return _numbered_heading_level(line), line.strip()

    return None


def _numbered_heading_level(line: str) -> int:
    numeric = re.match(r"^\d+(\.\d+){0,4}", line)
    if numeric:
        return min(numeric.group(0).count(".") + 1, 6)
    if line.startswith("第") and ("章" in line[:8] or "节" in line[:8]):
        return 1
    return 2


def _with_heading_context(content: str, title_path: List[str]) -> str:
    if not title_path:
        return content
    return f"章节路径：{' > '.join(title_path)}\n{content}"


def _content_hash(content: str) -> str:
    normalized = re.sub(r"\s+", " ", content).strip()
    return hashlib.sha1(normalized.encode("utf-8")).hexdigest()


def _estimate_token_count(content: str) -> int:
    tokens = re.findall(r"[\u4e00-\u9fff]|[a-zA-Z0-9_]+|[^\s]", content)
    return max(1, len(tokens))
