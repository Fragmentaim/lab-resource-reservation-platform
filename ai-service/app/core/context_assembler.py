from typing import List, Optional

from app.config import settings
from app.core.token_counter import count_tokens, truncate_by_tokens
from app.core.prompt import SYSTEM_PROMPT, USER_PROMPT_TEMPLATE


def assemble_context(
    original_question: str,
    rewritten_question: str,
    ranked_results: List[dict],
    session_summary: Optional[str],
    chat_history: Optional[List[dict]],
    top_k: int,
    context_options: Optional[dict] = None,
) -> dict:
    options = _resolve_options(context_options)

    summary_text, summary_tokens, summary_truncated = truncate_by_tokens(
        session_summary or "",
        options["summary_max_tokens"],
    )

    question_text = original_question
    if rewritten_question and rewritten_question != original_question:
        question_text = (
            f"用户原始问题：{original_question}\n"
            f"用于检索的独立问题：{rewritten_question}\n"
            "请回答用户原始问题。"
        )
    user_prompt = USER_PROMPT_TEMPLATE.format(question=question_text)
    max_allowed = max(1, options["context_window_tokens"]
                      - options["max_output_tokens"] - options["safety_margin_tokens"])
    base_context_sections = []
    if summary_text:
        base_context_sections.append("【会话摘要】\n" + summary_text)
    base_context_sections.append("【检索证据】\n")
    base_system_prompt = SYSTEM_PROMPT.format(context="\n\n".join(base_context_sections))
    fixed_tokens = count_tokens(base_system_prompt) + count_tokens(user_prompt)
    # RAG has no static evidence bucket. It gets the space its actual selected
    # chunks need, then recent complete turns occupy the remaining window.
    available_evidence_tokens = max(0, max_allowed - fixed_tokens)

    evidence_text, sources, dropped_sources, evidence_tokens = _pack_evidence(
        ranked_results,
        top_k,
        available_evidence_tokens,
    )

    context_sections = []
    if summary_text:
        context_sections.append("【会话摘要】\n" + summary_text)
    context_sections.append("【检索证据】\n" + (evidence_text or "未找到相关文档内容。"))
    context = "\n\n".join(context_sections)

    system_prompt = SYSTEM_PROMPT.format(context=context)

    system_tokens = count_tokens(system_prompt)
    user_tokens = count_tokens(user_prompt)
    history_messages, history_tokens, history_dropped = _pack_history(
        chat_history or [],
        max(0, max_allowed - system_tokens - user_tokens),
    )
    total_prompt_tokens = system_tokens + user_tokens + history_tokens

    stats = {
        "context_window_tokens": options["context_window_tokens"],
        "max_output_tokens": options["max_output_tokens"],
        "safety_margin_tokens": options["safety_margin_tokens"],
        # Retained for Java-side context trace compatibility.
        "max_prompt_tokens": options["context_window_tokens"],
        "answer_reserve_tokens": options["max_output_tokens"],
        "summary_tokens": summary_tokens,
        "history_tokens": history_tokens,
        "evidence_tokens": evidence_tokens,
        "available_evidence_tokens": available_evidence_tokens,
        "system_tokens": system_tokens,
        "user_tokens": user_tokens,
        "total_prompt_tokens": total_prompt_tokens,
        "prompt_budget_tokens": max_allowed,
        "over_budget": total_prompt_tokens > max_allowed,
        "summary_truncated": summary_truncated,
        "history_turns_used": _count_user_turns(history_messages),
        "history_messages_used": len(history_messages),
        "history_messages_dropped": history_dropped,
        "selected_source_count": len(sources),
        "dropped_source_count": len(dropped_sources),
        "selected_sources": [_source_snapshot(source, "selected") for source in sources],
        "dropped_sources": dropped_sources[:20],
    }

    return {
        "system_prompt": system_prompt,
        "user_prompt": user_prompt,
        "history": history_messages,
        "sources": sources,
        "context_stats": stats,
    }


def summarize_sources_for_trace(stats: dict) -> dict:
    if not stats:
        return {}
    return {
        "selected_sources": stats.get("selected_sources", []),
        "dropped_sources": stats.get("dropped_sources", []),
    }


def _resolve_options(options: Optional[dict]) -> dict:
    raw = options or {}
    return {
        "context_window_tokens": int(raw.get("context_window_tokens") or raw.get("max_prompt_tokens")
                                     or settings.context_window_tokens),
        "max_output_tokens": int(raw.get("max_output_tokens") or raw.get("answer_reserve_tokens")
                                 or settings.max_output_tokens),
        "safety_margin_tokens": int(raw.get("safety_margin_tokens") or settings.safety_margin_tokens),
        "summary_max_tokens": int(raw.get("summary_max_tokens") or raw.get("summary_budget_tokens")
                                  or settings.summary_max_tokens),
    }


def _pack_history(history: List[dict], budget: int) -> tuple[List[dict], int, int]:
    turns = _group_complete_turns(history)
    selected_reversed = []
    used_tokens = 0
    dropped = 0
    for turn in reversed(turns):
        turn_tokens = sum(count_tokens(message["content"]) + 4 for message in turn)
        if used_tokens + turn_tokens > budget:
            dropped += 1
            continue
        selected_reversed.append(turn)
        used_tokens += turn_tokens

    selected = [message for turn in reversed(selected_reversed) for message in turn]
    return selected, used_tokens, dropped


def _group_complete_turns(history: List[dict]) -> List[List[dict]]:
    """Keep user/assistant exchanges intact so prompt trimming never leaves an orphaned reply."""
    turns = []
    current = []
    for message in history:
        role = message.get("role")
        content = (message.get("content") or "").strip()
        if role not in ("user", "assistant") or not content:
            continue
        if role == "user":
            if current:
                turns.append(current)
            current = [{"role": "user", "content": content}]
        elif current:
            current.append({"role": "assistant", "content": content})
    if current:
        turns.append(current)
    return turns


def _pack_evidence(results: List[dict], top_k: int, budget: int) -> tuple[str, List[dict], List[dict], int]:
    parts = []
    selected = []
    dropped = []
    used_tokens = 0

    for index, result in enumerate(results):
        source_text = _format_source(index + 1, result)
        source_tokens = count_tokens(source_text)
        if len(selected) >= top_k:
            dropped.append(_source_snapshot(result, "outside_top_k"))
            continue
        if selected and used_tokens + source_tokens > budget:
            dropped.append(_source_snapshot(result, "evidence_budget"))
            continue
        if source_tokens > budget:
            content, _, _ = truncate_by_tokens(result.get("content") or "", max(1, budget - 120))
            result = dict(result)
            result["content"] = content
            source_text = _format_source(index + 1, result)
            source_tokens = count_tokens(source_text)

        parts.append(source_text)
        selected.append(result)
        used_tokens += source_tokens

    return "\n\n---\n\n".join(parts), selected, dropped, used_tokens


def _format_source(index: int, result: dict) -> str:
    header_parts = [
        f"文档ID: {result.get('document_id')}",
        f"页码: {result.get('page_no', 'N/A')}",
    ]
    if result.get("section_title"):
        header_parts.append(f"章节: {result['section_title']}")
    if result.get("chunk_id"):
        header_parts.append(f"chunkId: {result['chunk_id']}")
    return f"[来源 {index}] ({', '.join(header_parts)})\n{result.get('content') or ''}"


def _source_snapshot(result: dict, reason: str) -> dict:
    return {
        "reason": reason,
        "document_id": result.get("document_id"),
        "chunk_id": result.get("chunk_id"),
        "score": result.get("score"),
        "rerank_score": result.get("rerank_score"),
        "retrieval_score": result.get("retrieval_score"),
        "section_title": result.get("section_title"),
    }


def _count_user_turns(messages: List[dict]) -> int:
    return sum(1 for message in messages if message.get("role") == "user")
