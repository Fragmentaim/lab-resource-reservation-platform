from typing import List, Optional

from app.core import llm
from app.core.token_counter import count_tokens


WORKING_MEMORY_PROMPT = """你是 Agent 的会话工作记忆压缩器，不是泛化的聊天摘要器。
你的输出会替代一段较早的对话，供后续 Agent 做检索、工具规划和安全判断；遗漏一个 ID、时间、否定条件或操作边界都会造成错误。

先在内部完成以下合并：
1. 从旧摘要与新增对话提取所有原子状态；一个编号、日期、资源、时段、时长、权限、决定或限制各是一条状态。
2. 新增对话只有在明确变更、撤销或替代时，才能覆盖旧状态；被覆盖的值必须留下“已作废/不可恢复”的关系。
3. 用户的禁止、仅允许、需确认、不得执行等安全边界优先级最高，不能因为措辞简短而省略。
4. 未被明确撤销的旧摘要信息默认继续保留。不要根据常识补全、合并或猜测。

严格只按以下 Markdown 结构输出，每个标题都保留；没有内容写“无”。每条列表只写一个原子状态，保留编号、数字、日期、时段和否定词的原样文本：
## 当前目标
## 当前事实与实体
## 当前决定与覆盖关系
## 强约束、禁止与权限
## 安全边界与确认条件
## 待办与未解决问题
## 证据线索

附加规则：
- “当前决定与覆盖关系”必须同时写清当前值与已作废值，例如“当前：X；已作废且不得恢复：Y”。
- “强约束、禁止与权限”必须逐条保留时长、时间、排除项、角色范围、可见/不可见范围、只读/仅预检等限制。
- “安全边界与确认条件”必须逐条保留不得真实写入、不得取消、需用户确认、不得臆造 ID 等要求。
- 只把用户明确陈述或工具/知识库已返回的内容写为事实；证据线索只保留文档、章节、chunk 等定位信息。
- 不要写解释、寒暄、推理过程或“以上为总结”，只输出工作记忆正文。"""


def summarize_session(
    existing_summary: Optional[str],
    new_turns: List[dict],
) -> dict:
    turns_text = _format_turns(new_turns)
    if not turns_text and existing_summary:
        return {
            "summary": existing_summary,
            "summary_tokens": count_tokens(existing_summary),
            "provider_usage": {"reported": False, "model_call_count": 0},
        }

    user_prompt = f"""旧摘要：
{existing_summary or "无"}

新增对话：
{turns_text or "无"}

请输出更新后的会话摘要："""

    summary, provider_usage = llm.chat_with_usage(system_prompt=WORKING_MEMORY_PROMPT, user_message=user_prompt)
    usage = dict(provider_usage or {})
    usage["model_call_count"] = 1
    return {
        "summary": summary,
        "summary_tokens": count_tokens(summary),
        "provider_usage": usage,
    }


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
