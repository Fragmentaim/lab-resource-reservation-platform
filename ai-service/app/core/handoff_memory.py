import json
import re
from typing import Optional

from app.core.model_text import visible_model_text


HANDOFF_HEADER = "# Agent 会话交接 v1"
_LEGACY_PROTECTED_ANCHOR_HEADER = "## 受保护锚点（程序校验）"
_CODE_FENCE = re.compile(r"^```(?:markdown|md)?\s*|\s*```$", re.I)


def normalize_handoff(raw: str) -> str:
    """Return a validated Markdown handoff without accepting explanatory preambles."""
    text = visible_model_text(raw)
    if text.startswith("```"):
        text = _CODE_FENCE.sub("", text).strip()
    if not text.startswith(HANDOFF_HEADER):
        raise ValueError(f"输出首行必须是 {HANDOFF_HEADER}")
    first_line = text.splitlines()[0].strip()
    if first_line != HANDOFF_HEADER:
        raise ValueError(f"输出首行必须严格等于 {HANDOFF_HEADER}")
    if len(text.splitlines()) < 2:
        raise ValueError("交接记录缺少正文")
    return text


def strip_legacy_protected_anchor_section(raw: Optional[str]) -> str:
    """Remove the retired machine-anchor section from an existing handoff."""
    if not raw:
        return ""
    lines = raw.splitlines()
    kept = []
    skipping = False
    for line in lines:
        if line.strip() == _LEGACY_PROTECTED_ANCHOR_HEADER:
            skipping = True
            continue
        if skipping and line.strip().startswith("## "):
            skipping = False
        if not skipping:
            kept.append(line)
    return "\n".join(kept).strip()


def is_legacy_working_memory(raw: Optional[str]) -> bool:
    if not raw or not raw.lstrip().startswith("{"):
        return False
    try:
        payload = json.loads(raw)
    except json.JSONDecodeError:
        return False
    return isinstance(payload, dict) and payload.get("schema_version") == 2
