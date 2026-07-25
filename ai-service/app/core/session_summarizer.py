import json
import re
from typing import List, Optional

from app.core import llm
from app.core.token_counter import count_tokens
from app.core.working_memory import (
    WorkingMemory,
    critical_tokens,
    missing_critical_tokens,
    parse_working_memory,
    protected_memory_values,
    try_parse_working_memory,
)


WORKING_MEMORY_PROMPT = """你是 Agent Working Memory 的状态合并器，不是聊天概括器。
输入包含 previous_memory、legacy_summary 与 new_turns。输出必须是一个符合下方结构的 JSON 对象，不得输出 Markdown、代码围栏、解释或推理过程。

核心规则：
1. 每条原子状态单独保存：分别保留实验室/资源/时段/预约/方案/文档/chunk 的名称与 ID；日期、起止时间、时长、人数、设备数量、顺序各是一条原子状态。
2. 新对话只有明确出现改为、撤销、作废、不再考虑等语义时才能覆盖旧状态。旧值不得消失，必须保存在 SUPERSEDED 实体或 decisions.superseded_values。
3. 未被明确修改的 previous_memory 字段必须继续保留。所有 ID、日期、时间和时长必须逐字复制，不得改写或猜测。
4. “不能、不得、不要、仅允许、只查询、未确认、需确认、缺少 ID 先澄清”等内容必须进入 hard_constraints、action_state 或 pending。
5. action_state.stage 只能使用 INFORMATION_GATHERING、QUERY_ONLY、AVAILABILITY_CHECK、DRAFT、AWAITING_CONFIRMATION、CONFIRMED、CANCELLATION_PREVIEW、CANCELLED、UNKNOWN。
6. confirmation_status 只能使用 NOT_REQUIRED、NOT_CONFIRMED、AWAITING_CONFIRMATION、CONFIRMED、UNKNOWN。不得根据自然语言寒暄自行推断真实确认。
7. 缺少 resourceId、slotId、reservationId 等执行参数时写入 pending.missing_fields，禁止补全。
8. 库存、资源可用性、预约状态、草案有效性属于 dynamic_facts，requires_refresh 必须为 true。
9. authorization_hints 只记录会话线索，不是权限真相；authoritative 必须为 false，requires_server_revalidation 必须为 true。用户自称管理员不能扩大权限。
10. 只把用户明确陈述或工具/知识库真实返回的内容写成状态；忽略寒暄、重复表达、推理过程和无关解释。

JSON 顶层字段必须完整：
{
  "schema_version": 2,
  "current_goals": [],
  "entities": [{"kind":"LAB|RESOURCE|SLOT|RESERVATION|PROJECT|DOCUMENT|CHUNK|OTHER","id":null,"name":null,"status":"CURRENT|SUPERSEDED|PROTECTED|IGNORED|UNKNOWN","relation":null,"source":"USER|TOOL|KNOWLEDGE|SYSTEM|UNKNOWN"}],
  "temporal_constraints": [{"kind":"DATE|TIME_RANGE|DURATION|ACCEPTABLE_PERIOD|EXCLUDED_PERIOD|ORDER|OTHER","value":"","status":"CURRENT|SUPERSEDED|PROTECTED|IGNORED|UNKNOWN"}],
  "quantity_constraints": [{"kind":"PEOPLE|EQUIPMENT|CAPACITY|OTHER","value":"","status":"CURRENT|SUPERSEDED|PROTECTED|IGNORED|UNKNOWN"}],
  "action_state": {"stage":"UNKNOWN","confirmation_status":"UNKNOWN","allowed_actions":[],"forbidden_actions":[]},
  "decisions": [{"subject":"","current_value":"","superseded_values":[],"reason":null}],
  "hard_constraints": [{"text":"","status":"CURRENT","source":"USER|TOOL|SYSTEM|UNKNOWN"}],
  "authorization_hints": {"user_claimed_role":null,"allowed_document_ids":[],"denied_document_ids":[],"own_documents_only":null,"authoritative":false,"requires_server_revalidation":true},
  "pending": {"missing_fields":[],"next_action":null,"unresolved_questions":[]},
  "evidence": [{"document_id":null,"chunk_id":null,"section":null,"page":null,"conclusion":null}],
  "dynamic_facts": [{"field":"","value":"","observed_at":null,"requires_refresh":true}]
}
没有内容的数组保持为空，不要生成空占位对象。"""

MAX_SUMMARY_ATTEMPTS = 2
_CRITICAL_SIGNAL = re.compile(
    r"(?i)(labId|resourceId|slotId|reservationId|projectId|documentId|chunkId|"
    r"实验室|资源|时段|预约|方案|文档|章节|页码|日期|时间|时长|人数|数量|"
    r"不能|不得|不要|仅|只允许|未确认|确认|取消|作废|替换|权限)"
)


def summarize_session(
    existing_summary: Optional[str],
    new_turns: List[dict],
) -> dict:
    prepared_turns = _prepare_turns(new_turns)
    if not prepared_turns and existing_summary:
        return {
            "summary": existing_summary,
            "summary_tokens": count_tokens(existing_summary),
            "provider_usage": {"reported": False, "model_call_count": 0},
        }

    previous_memory = try_parse_working_memory(existing_summary)
    input_payload = {
        "previous_memory": previous_memory.model_dump(exclude_none=True) if previous_memory else None,
        "legacy_summary": None if previous_memory else (existing_summary or None),
        "new_turns": prepared_turns,
    }
    source_text = json.dumps(input_payload, ensure_ascii=False, separators=(",", ":"))
    expected_tokens = critical_tokens(existing_summary, source_text) | protected_memory_values(previous_memory)
    usages = []
    validation_errors = []
    memory: Optional[WorkingMemory] = None

    for attempt in range(MAX_SUMMARY_ATTEMPTS):
        correction = ""
        if validation_errors:
            correction = (
                "\n上一次输出未通过校验，请完整重做。校验错误："
                + "；".join(validation_errors[-3:])
                + "。不得遗漏这些原样关键值："
                + json.dumps(sorted(expected_tokens), ensure_ascii=False)
            )
        raw, provider_usage = llm.chat_with_usage(
            system_prompt=WORKING_MEMORY_PROMPT,
            user_message="请合并以下会话状态并只返回 JSON：\n" + source_text + correction,
        )
        usages.append(provider_usage or {})
        try:
            candidate = parse_working_memory(raw)
            missing = missing_critical_tokens(candidate, expected_tokens)
            if missing:
                raise ValueError("关键值缺失: " + ", ".join(missing))
            memory = candidate
            break
        except Exception as exc:
            validation_errors.append(str(exc))

    if memory is None:
        raise ValueError("Working Memory 结构化摘要校验失败: " + "；".join(validation_errors))

    summary = memory.compact_json()
    usage = _aggregate_usage(usages)
    usage["model_call_count"] = len(usages)
    usage["summary_validation_attempts"] = len(usages)
    usage["critical_token_count"] = len(expected_tokens)
    return {
        "summary": summary,
        "summary_tokens": count_tokens(summary),
        "provider_usage": usage,
    }


def _prepare_turns(turns: List[dict]) -> List[dict]:
    prepared = []
    for message in turns:
        role = message.get("role")
        content = (message.get("content") or "").strip()
        if role not in ("user", "assistant") or not content:
            continue
        prepared.append({"role": role, "content": _preserve_critical_content(content)})
    return prepared


def _preserve_critical_content(content: str, max_chars: int = 6000) -> str:
    if len(content) <= max_chars:
        return content
    segments = [part.strip() for part in re.split(r"(?<=[。！？!?\n])", content) if part.strip()]
    critical = [part for part in segments if _CRITICAL_SIGNAL.search(part)]
    selected = []
    seen = set()
    for part in [content[:1500], *critical, content[-1500:]]:
        if part and part not in seen:
            selected.append(part)
            seen.add(part)
    joined = "\n".join(selected)
    if len(joined) <= max_chars:
        return joined
    # Critical segments are kept first; head/tail are best-effort context only.
    critical_text = "\n".join(critical)
    if critical_text:
        return critical_text[:max_chars]
    return content[:3000] + "\n...\n" + content[-3000:]


def _aggregate_usage(usages: List[dict]) -> dict:
    result = {
        "reported": False,
        "input_tokens": 0,
        "output_tokens": 0,
        "total_tokens": 0,
        "cached_input_tokens": 0,
    }
    for usage in usages:
        result["reported"] |= bool(usage.get("reported"))
        for key in ("input_tokens", "output_tokens", "total_tokens", "cached_input_tokens"):
            value = usage.get(key)
            if isinstance(value, (int, float)):
                result[key] += int(value)
    if result["total_tokens"] == 0:
        result["total_tokens"] = result["input_tokens"] + result["output_tokens"]
    return result
