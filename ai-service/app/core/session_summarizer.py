from typing import List, Optional

from app.config import settings
from app.core import llm
from app.core.token_counter import count_tokens, truncate_by_tokens


def summarize_session(
    existing_summary: Optional[str],
    new_turns: List[dict],
    max_summary_tokens: Optional[int] = None,
) -> dict:
    budget = max_summary_tokens or settings.summary_max_tokens
    turns_text = _format_turns(new_turns)
    if not turns_text and existing_summary:
        summary, tokens, _ = truncate_by_tokens(existing_summary, budget)
        return {"summary": summary, "summary_tokens": tokens}

    system_prompt = """你是会话工作记忆压缩器。请把知识库问答会话压缩成可供下一轮 Agent 使用的结构化记忆。
严格按以下 Markdown 标题输出，未出现的信息写“无”：
## 用户目标
## 已确认事实
## 已作决定
## 约束与权限
## 待办与未解决问题
## 证据线索

要求：
1. 已确认事实必须来自已有对话或工具/知识库结果；不要把猜测写成事实
2. 证据线索只保留文档、章节、chunk 等定位信息，不复述大段原文
3. 不改变用户已确认的意图、决定或约束
4. 用中文，信息密度优先；只输出工作记忆正文"""
    user_prompt = f"""旧摘要：
{existing_summary or "无"}

新增对话：
{turns_text or "无"}

请输出更新后的会话摘要："""

    summary = llm.chat(system_prompt=system_prompt, user_message=user_prompt)
    summary, tokens, _ = truncate_by_tokens(summary, budget)
    return {"summary": summary, "summary_tokens": tokens}


def _format_turns(turns: List[dict]) -> str:
    lines = []
    for message in turns:
        role = message.get("role")
        content = (message.get("content") or "").strip()
        if not content:
            continue
        label = "用户" if role == "user" else "助手"
        lines.append(f"{label}: {content[:1200]}")
    return "\n".join(lines)
