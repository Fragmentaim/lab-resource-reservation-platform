import hashlib
import re
from typing import List, Tuple
from app.config import settings
from app.core.document_ir import DocumentBlock, ParsedDocument


_HEADING_RE = re.compile(r"^(#{1,6})\s+(.+?)\s*$")
_NUMBERED_HEADING_RE = re.compile(
    r"^((第[一二三四五六七八九十百千0-9]+[章节条])|([一二三四五六七八九十]+[、.])|(\d+(\.\d+){0,4}[、.\s]))\s*(.+)$"
)


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
        if not block.content.strip():
            continue
        for section in _extract_sections(block.content, block.location.unit_no, block.title_path):
            parts = splitter.split_text(section["content"])
            cursor = 0
            for part in parts:
                part = part.strip()
                if not part:
                    continue

                char_start = section["content"].find(part, cursor)
                if char_start < 0:
                    char_start = cursor
                char_end = char_start + len(part)
                cursor = char_end

                title_path = section["title_path"]
                content = _with_heading_context(part, title_path)
                content_hash = _content_hash(content)

                chunks.append({
                    "chunk_index": chunk_index,
                    "content": content,
                    "page_no": block.location.unit_no,
                    "section_title": title_path[-1] if title_path else None,
                    "title_path": title_path,
                    "title_path_text": " > ".join(title_path),
                    "content_hash": content_hash,
                    "char_start": section["char_start"] + char_start,
                    "char_end": section["char_start"] + char_end,
                    "token_count": _estimate_token_count(content),
                    "block_type": block.block_type,
                    "source_location": block.location.as_dict(),
                    "parser_provider": document.quality.provider,
                })
                chunk_index += 1

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


def _extract_sections(text: str, page_no: int, initial_title_path: List[str] | None = None) -> List[dict]:
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
                sections.append(_build_section(current_lines, page_no, current_path, current_start))
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
        sections.append(_build_section(current_lines, page_no, current_path, current_start))

    if sections:
        return [s for s in sections if s["content"]]

    content = text.strip()
    return [{
        "content": content,
        "page_no": page_no,
        "title_path": [],
        "char_start": 0,
    }] if content else []


def _build_section(lines: List[str], page_no: int, title_path: List[str], char_start: int) -> dict:
    content = "\n".join(lines).strip()
    return {
        "content": content,
        "page_no": page_no,
        "title_path": list(title_path),
        "char_start": char_start,
    }


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
