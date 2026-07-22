"""Small translations between the project's OpenAI-shaped internal trace and Anthropic Messages."""

import json
from typing import Any, Dict, Iterable, List


def to_anthropic_tools(tools: Iterable[Dict[str, Any]]) -> List[Dict[str, Any]]:
    result: List[Dict[str, Any]] = []
    for tool in tools:
        function = tool.get("function", tool)
        if not isinstance(function, dict) or not function.get("name"):
            continue
        result.append({
            "name": function["name"],
            "description": function.get("description", ""),
            "input_schema": function.get("parameters") or {
                "type": "object", "properties": {}, "additionalProperties": True
            },
        })
    return result


def to_anthropic_messages(messages: Iterable[Dict[str, Any]]) -> List[Dict[str, Any]]:
    result: List[Dict[str, Any]] = []

    def append(role: str, content: Any) -> None:
        blocks = content if isinstance(content, list) else [{"type": "text", "text": str(content or "")}]
        if not blocks:
            return
        if result and result[-1]["role"] == role:
            result[-1]["content"].extend(blocks)
        else:
            result.append({"role": role, "content": blocks})

    for message in messages:
        role = message.get("role")
        if role == "system":
            continue
        if role == "assistant" and message.get("tool_calls"):
            blocks = []
            if message.get("content"):
                blocks.append({"type": "text", "text": str(message["content"])})
            for call in message["tool_calls"]:
                function = call.get("function", {})
                try:
                    arguments = json.loads(function.get("arguments") or "{}")
                except (TypeError, json.JSONDecodeError):
                    arguments = {}
                blocks.append({"type": "tool_use", "id": call.get("id", ""),
                               "name": function.get("name", ""), "input": arguments})
            append("assistant", blocks)
        elif role == "tool":
            append("user", [{"type": "tool_result", "tool_use_id": message.get("tool_call_id", ""),
                             "content": str(message.get("content", ""))}])
        elif role in {"user", "assistant"}:
            append(role, message.get("content", ""))
    return result


def response_blocks(response: Any) -> list[Any]:
    return list(getattr(response, "content", None) or [])


def block_value(block: Any, key: str, default: Any = None) -> Any:
    if isinstance(block, dict):
        return block.get(key, default)
    return getattr(block, key, default)


def response_text(response: Any) -> str:
    return "".join(str(block_value(block, "text", "")) for block in response_blocks(response)
                   if block_value(block, "type", "") == "text")


def response_tool_uses(response: Any) -> list[dict[str, Any]]:
    result = []
    for block in response_blocks(response):
        if block_value(block, "type", "") != "tool_use":
            continue
        value = block_value(block, "input", {})
        result.append({"id": block_value(block, "id", ""), "name": block_value(block, "name", ""),
                       "input": value if isinstance(value, dict) else {}})
    return result
