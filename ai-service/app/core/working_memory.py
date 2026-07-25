import json
import re
from typing import Literal, Optional

from pydantic import BaseModel, ConfigDict, Field, ValidationError, field_validator


MemoryStatus = Literal["CURRENT", "SUPERSEDED", "PROTECTED", "IGNORED", "UNKNOWN"]
EntityKind = Literal["LAB", "RESOURCE", "SLOT", "RESERVATION", "PROJECT", "DOCUMENT", "CHUNK", "OTHER"]
ActionStage = Literal[
    "INFORMATION_GATHERING",
    "QUERY_ONLY",
    "AVAILABILITY_CHECK",
    "DRAFT",
    "AWAITING_CONFIRMATION",
    "CONFIRMED",
    "CANCELLATION_PREVIEW",
    "CANCELLED",
    "UNKNOWN",
]
ConfirmationStatus = Literal["NOT_REQUIRED", "NOT_CONFIRMED", "AWAITING_CONFIRMATION", "CONFIRMED", "UNKNOWN"]


class MemoryModel(BaseModel):
    model_config = ConfigDict(extra="forbid")


class EntityState(MemoryModel):
    kind: EntityKind
    id: Optional[str] = None
    name: Optional[str] = None
    status: MemoryStatus = "CURRENT"
    relation: Optional[str] = None
    source: Literal["USER", "TOOL", "KNOWLEDGE", "SYSTEM", "UNKNOWN"] = "UNKNOWN"

    @field_validator("id", "name", "relation")
    @classmethod
    def strip_optional_text(cls, value: Optional[str]) -> Optional[str]:
        stripped = value.strip() if value else ""
        return stripped or None


class TemporalConstraint(MemoryModel):
    kind: Literal["DATE", "TIME_RANGE", "DURATION", "ACCEPTABLE_PERIOD", "EXCLUDED_PERIOD", "ORDER", "OTHER"]
    value: str
    status: MemoryStatus = "CURRENT"


class QuantityConstraint(MemoryModel):
    kind: Literal["PEOPLE", "EQUIPMENT", "CAPACITY", "OTHER"]
    value: str
    status: MemoryStatus = "CURRENT"


class ActionState(MemoryModel):
    stage: ActionStage = "UNKNOWN"
    confirmation_status: ConfirmationStatus = "UNKNOWN"
    allowed_actions: list[str] = Field(default_factory=list)
    forbidden_actions: list[str] = Field(default_factory=list)


class DecisionState(MemoryModel):
    subject: str
    current_value: str
    superseded_values: list[str] = Field(default_factory=list)
    reason: Optional[str] = None


class ConstraintState(MemoryModel):
    text: str
    status: MemoryStatus = "CURRENT"
    source: Literal["USER", "TOOL", "SYSTEM", "UNKNOWN"] = "UNKNOWN"


class AuthorizationHints(MemoryModel):
    user_claimed_role: Optional[str] = None
    allowed_document_ids: list[str] = Field(default_factory=list)
    denied_document_ids: list[str] = Field(default_factory=list)
    own_documents_only: Optional[bool] = None
    authoritative: bool = False
    requires_server_revalidation: bool = True


class PendingState(MemoryModel):
    missing_fields: list[str] = Field(default_factory=list)
    next_action: Optional[str] = None
    unresolved_questions: list[str] = Field(default_factory=list)


class EvidenceReference(MemoryModel):
    document_id: Optional[str] = None
    chunk_id: Optional[str] = None
    section: Optional[str] = None
    page: Optional[str] = None
    conclusion: Optional[str] = None


class DynamicFact(MemoryModel):
    field: str
    value: str
    observed_at: Optional[str] = None
    requires_refresh: bool = True


class WorkingMemory(MemoryModel):
    schema_version: Literal[2] = 2
    current_goals: list[str] = Field(default_factory=list)
    entities: list[EntityState] = Field(default_factory=list)
    temporal_constraints: list[TemporalConstraint] = Field(default_factory=list)
    quantity_constraints: list[QuantityConstraint] = Field(default_factory=list)
    action_state: ActionState = Field(default_factory=ActionState)
    decisions: list[DecisionState] = Field(default_factory=list)
    hard_constraints: list[ConstraintState] = Field(default_factory=list)
    authorization_hints: AuthorizationHints = Field(default_factory=AuthorizationHints)
    pending: PendingState = Field(default_factory=PendingState)
    evidence: list[EvidenceReference] = Field(default_factory=list)
    dynamic_facts: list[DynamicFact] = Field(default_factory=list)

    def safe_normalized(self) -> "WorkingMemory":
        """Server-owned authorization and dynamic freshness cannot be relaxed by the model."""
        payload = self.model_dump()
        payload["authorization_hints"]["authoritative"] = False
        payload["authorization_hints"]["requires_server_revalidation"] = True
        for fact in payload["dynamic_facts"]:
            fact["requires_refresh"] = True
        return WorkingMemory.model_validate(payload)

    def compact_json(self) -> str:
        return json.dumps(self.model_dump(exclude_none=True), ensure_ascii=False, separators=(",", ":"))


_IDENTIFIER_PATTERNS = [
    re.compile(
        r"(?i)\b(?:labId|resourceId|slotId|reservationId|projectId|documentId|chunkId|"
        r"lab_id|resource_id|slot_id|reservation_id|project_id|document_id|chunk_id)"
        r"\s*[:=：]\s*[\"']?([A-Za-z0-9][A-Za-z0-9._:/-]*)"
    ),
    re.compile(r"\b[A-Z][A-Z0-9]*(?:[-_][A-Z0-9]+)+\b"),
]
_DATE_PATTERN = re.compile(r"\b\d{4}[-/.年]\d{1,2}(?:[-/.月]\d{1,2}日?)?\b")
_TIME_PATTERN = re.compile(r"\b\d{1,2}:\d{2}(?::\d{2})?\b")
_DURATION_PATTERN = re.compile(r"\b\d+(?:\.\d+)?\s*(?:分钟|小时|天|minutes?|hours?|days?)\b", re.I)
_NON_DOMAIN_TOKENS = {
    "INFORMATION_GATHERING", "QUERY_ONLY", "AVAILABILITY_CHECK", "AWAITING_CONFIRMATION",
    "CANCELLATION_PREVIEW", "NOT_REQUIRED", "NOT_CONFIRMED",
}


def parse_working_memory(raw: str) -> WorkingMemory:
    payload = _extract_json_object(raw)
    return WorkingMemory.model_validate(payload).safe_normalized()


def try_parse_working_memory(raw: Optional[str]) -> Optional[WorkingMemory]:
    if not raw or not raw.strip():
        return None
    try:
        return parse_working_memory(raw)
    except (ValueError, ValidationError, json.JSONDecodeError):
        return None


def critical_tokens(*texts: Optional[str]) -> set[str]:
    tokens: set[str] = set()
    for text in texts:
        if not text:
            continue
        for pattern in _IDENTIFIER_PATTERNS:
            for match in pattern.findall(text):
                value = match if isinstance(match, str) else next((part for part in match if part), "")
                if value and value not in _NON_DOMAIN_TOKENS:
                    tokens.add(value)
        tokens.update(_DATE_PATTERN.findall(text))
        tokens.update(_TIME_PATTERN.findall(text))
        tokens.update(_DURATION_PATTERN.findall(text))
    return tokens


def missing_critical_tokens(memory: WorkingMemory, expected: set[str]) -> list[str]:
    serialized = memory.compact_json()
    return sorted(token for token in expected if token not in serialized)


def protected_memory_values(memory: Optional[WorkingMemory]) -> set[str]:
    """Values that may change status but must not silently disappear across compactions."""
    if memory is None:
        return set()
    values: set[str] = set()

    def add(value: Optional[str]) -> None:
        text = value.strip() if value else ""
        if text:
            values.add(text)

    for entity in memory.entities:
        add(entity.id)
        add(entity.name)
    for item in memory.temporal_constraints:
        add(item.value)
    for item in memory.quantity_constraints:
        add(item.value)
    for item in memory.decisions:
        add(item.current_value)
        for value in item.superseded_values:
            add(value)
    for item in memory.hard_constraints:
        add(item.text)
    for item in memory.evidence:
        add(item.document_id)
        add(item.chunk_id)
        add(item.section)
        add(item.page)
        add(item.conclusion)
    return values


def _extract_json_object(raw: str) -> dict:
    text = (raw or "").strip()
    if text.startswith("```"):
        text = re.sub(r"^```(?:json)?\s*", "", text, flags=re.I)
        text = re.sub(r"\s*```$", "", text)
    decoder = json.JSONDecoder()
    for index, char in enumerate(text):
        if char != "{":
            continue
        try:
            payload, _ = decoder.raw_decode(text[index:])
            if isinstance(payload, dict):
                return payload
        except json.JSONDecodeError:
            continue
    raise ValueError("summary model did not return a JSON object")
