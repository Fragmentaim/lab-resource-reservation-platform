import re
from typing import List, Optional

from app.config import settings
from app.core import llm


_FOLLOW_UP_PATTERNS = [
    r"^(那|这个|这个呢|它|它呢|上面|刚才|继续|还有|再说|详细|限制|任务|规则)",
    r"(它|这个|上述|前面|刚才|这些|那些)",
]


def rewrite_question(
    question: str,
    session_summary: Optional[str] = None,
    chat_history: Optional[List[dict]] = None,
    model: Optional[str] = None,
) -> tuple[str, bool]:
    if not settings.enable_query_rewrite or not _should_rewrite(question):
        return question, False

    recent = _format_recent_history(chat_history or [], max_turns=4)
    system_prompt = """你是检索查询改写器。请把用户追问改写成可独立检索的中文问题。
要求：
1. 只输出改写后的问题，不要解释
2. 不要添加会话中没有出现过的实体
3. 如果原问题已经完整，原样返回"""
    user_prompt = f"""会话摘要：
{session_summary or "无"}

最近对话：
{recent or "无"}

用户当前问题：
{question}

改写后的独立检索问题："""

    try:
        rewritten = llm.chat(system_prompt=system_prompt, user_message=user_prompt, history=None, model=model)
        rewritten = _clean(rewritten)
        if not rewritten:
            return question, False
        if rewritten == question:
            return question, False
        return rewritten[:200], True
    except Exception as exc:
        print(f"[QueryRewrite] failed, fallback to original question: {exc}")
        return question, False


def _should_rewrite(question: str) -> bool:
    text = (question or "").strip()
    if not text:
        return False
    if len(text) <= 18:
        return True
    return any(re.search(pattern, text) for pattern in _FOLLOW_UP_PATTERNS)


def _format_recent_history(history: List[dict], max_turns: int) -> str:
    if not history:
        return ""
    selected = history[-max_turns * 2:]
    lines = []
    for message in selected:
        role = message.get("role")
        content = (message.get("content") or "").strip()
        if not content:
            continue
        label = "用户" if role == "user" else "助手"
        lines.append(f"{label}: {content[:500]}")
    return "\n".join(lines)


def _clean(text: str) -> str:
    value = (text or "").strip()
    value = value.strip("` \n\t")
    value = re.sub(r"^改写后的独立检索问题[:：]\s*", "", value)
    return value.splitlines()[0].strip() if value else ""
