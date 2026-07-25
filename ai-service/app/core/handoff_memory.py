import json
import re
from typing import Iterable, Optional


HANDOFF_HEADER = "# Agent 会话交接 v1"

_EXPLICIT_IDENTIFIER = re.compile(
    r"(?i)\b(?:labId|resourceId|slotId|reservationId|projectId|documentId|chunkId|"
    r"lab_id|resource_id|slot_id|reservation_id|project_id|document_id|chunk_id)"
    r"\s*[:=：]\s*[\"']?([A-Za-z0-9][A-Za-z0-9._:/-]*)"
)
_COMPOUND_IDENTIFIER = re.compile(
    r"\b(?=[A-Za-z0-9._:/-]*\d)[A-Za-z][A-Za-z0-9]*(?:[-_:/][A-Za-z0-9.]+)+\b"
)
_DATE = re.compile(r"(?<!\d)\d{4}[-/.年]\d{1,2}(?:[-/.月]\d{1,2}日?)?(?!\d)")
_TIME = re.compile(r"(?<!\d)\d{1,2}:\d{2}(?::\d{2})?(?!\d)")
_MEASURE = re.compile(
    r"(?<![\d.])\d+(?:\.\d+)?\s*(?:人|台|个|件|套|分钟|小时|天|次|"
    r"minutes?|hours?|days?|items?|people)",
    re.I,
)
_NAMED_VALUE = re.compile(
    r"(?:实验室|资源|设备|方案|项目|文档|章节|角色|身份|目标|首选地点|第一台|第二台)"
    r"(?:名称|编号)?\s*(?:是|为|改为|替换为|[:：])\s*"
    r"([^\s，。；;！？!?\n]{1,80})"
)
_QUOTED_VALUE = re.compile(r"[`“”「」『』\"]([^`“”「」『』\"\n]{1,80})[`“”「」『』\"]")
_SAFETY_SIGNAL = re.compile(
    r"(?:不能|不得|不要|禁止|仅允许|只允许|只查询|仅查询|最多|至少|"
    r"未确认|等待确认|需要确认|缺少.+?(?:先|需)澄清|无权|没有权限|"
    r"不要动|不再考虑|作废|取消预检|取消预览)"
)
_SOURCE_PREFIX = re.compile(r"^\s*(?:[-*+]\s+|\d+[.)、]\s*|\[(?:用户|工具|知识库|系统|未验证)\]\s*)+")
_CODE_FENCE = re.compile(r"^```(?:markdown|md)?\s*|\s*```$", re.I)

_LEGACY_VALUE_KEYS = {
    "current_goals", "id", "name", "relation", "value", "subject", "current_value", "superseded_values",
    "text", "allowed_actions", "forbidden_actions", "user_claimed_role",
    "allowed_document_ids", "denied_document_ids", "missing_fields", "next_action",
    "unresolved_questions", "document_id", "chunk_id", "section", "page", "conclusion",
    "field",
}
_IGNORED_ENUM_VALUES = {
    "CURRENT", "SUPERSEDED", "PROTECTED", "IGNORED", "UNKNOWN",
    "USER", "TOOL", "KNOWLEDGE", "SYSTEM",
}


def normalize_handoff(raw: str) -> str:
    """Return a validated Markdown handoff without accepting explanatory preambles."""
    text = (raw or "").strip()
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


def protected_anchors(existing_handoff: Optional[str], turns: Iterable[dict]) -> list[str]:
    """Extract exact high-risk literals that may not silently disappear."""
    texts = [existing_handoff or ""]
    texts.extend(str(turn.get("content") or "") for turn in turns)
    anchors: list[str] = []
    seen: set[str] = set()

    def add(value: object) -> None:
        text = str(value).strip() if value is not None else ""
        if not text or text in _IGNORED_ENUM_VALUES or text in seen:
            return
        seen.add(text)
        anchors.append(text)

    for text in texts:
        if not text:
            continue
        if is_legacy_working_memory(text):
            _add_legacy_json_anchors(text, add)
            continue
        for pattern in (_EXPLICIT_IDENTIFIER, _COMPOUND_IDENTIFIER, _DATE, _TIME, _MEASURE, _NAMED_VALUE, _QUOTED_VALUE):
            for match in pattern.findall(text):
                add(match)
        for sentence in re.split(r"(?<=[。！？!?；;\n])", text):
            for clause in re.split(r"[,，]", sentence):
                normalized = _SOURCE_PREFIX.sub("", clause).strip(" \t\r\n。！？!?；;")
                if 3 <= len(normalized) <= 100 and _SAFETY_SIGNAL.search(normalized):
                    add(normalized)
    return anchors


def missing_anchors(handoff: str, expected: Iterable[str]) -> list[str]:
    return [anchor for anchor in expected if anchor not in handoff]


def is_legacy_working_memory(raw: Optional[str]) -> bool:
    if not raw or not raw.lstrip().startswith("{"):
        return False
    try:
        payload = json.loads(raw)
    except json.JSONDecodeError:
        return False
    return isinstance(payload, dict) and payload.get("schema_version") == 2


def _add_legacy_json_anchors(raw: str, add) -> None:
    if not is_legacy_working_memory(raw):
        return
    payload = json.loads(raw)

    def visit(value: object, key: Optional[str] = None) -> None:
        if isinstance(value, dict):
            for child_key, child in value.items():
                visit(child, child_key)
            return
        if isinstance(value, list):
            for child in value:
                visit(child, key)
            return
        if key in _LEGACY_VALUE_KEYS and isinstance(value, (str, int, float)):
            add(value)

    visit(payload)
