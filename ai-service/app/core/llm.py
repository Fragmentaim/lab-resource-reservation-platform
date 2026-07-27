from app.config import settings
from app.core import model_gateway
from app.core.anthropic_compat import response_text, to_anthropic_messages
from app.core.model_text import visible_model_text
from typing import Iterator, List, Optional


def chat(
    system_prompt: str,
    user_message: str,
    history: Optional[List[dict]] = None,
    model: Optional[str] = None,
) -> str:
    """Send a chat completion request to OpenAI-compatible API."""
    content, _ = chat_with_usage(system_prompt, user_message, history, model)
    return content


def chat_with_usage(
    system_prompt: str,
    user_message: str,
    history: Optional[List[dict]] = None,
    model: Optional[str] = None,
) -> tuple[str, dict]:
    """Return provider-reported usage when the OpenAI-compatible gateway exposes it."""
    messages = [{"role": "system", "content": system_prompt}]

    if history:
        for msg in history:
            if msg["role"] in ("user", "assistant"):
                messages.append({"role": msg["role"], "content": msg["content"]})

    messages.append({"role": "user", "content": user_message})

    if settings.llm_api_mode == "responses":
        response, route = model_gateway.invoke(model, lambda client, resolved_model: client.responses.create(
            model=resolved_model,
            instructions=system_prompt,
            input=[
                {
                    "role": message["role"],
                    "content": [{"type": "input_text", "text": message["content"]}],
                }
                for message in messages
                if message["role"] != "system"
            ],
        ))
        return visible_model_text(response.output_text or ""), _usage_snapshot(getattr(response, "usage", None), route)

    if settings.llm_api_mode == "anthropic":
        system = "\n\n".join(message["content"] for message in messages if message["role"] == "system")
        response, route = model_gateway.invoke(model, lambda client, resolved_model: client.messages.create(
            model=resolved_model,
            max_tokens=max(1, settings.max_output_tokens),
            system=system,
            messages=to_anthropic_messages(messages),
            **model_gateway.anthropic_message_options(),
        ))
        return visible_model_text(response_text(response)), _usage_snapshot(getattr(response, "usage", None), route)

    response, route = model_gateway.invoke(model, lambda client, resolved_model: client.chat.completions.create(
        model=resolved_model,
        messages=messages,
        **model_gateway.chat_completion_options(),
    ))
    return visible_model_text(response.choices[0].message.content or ""), _usage_snapshot(
        getattr(response, "usage", None),
        route,
    )


def _usage_snapshot(usage, route: Optional[dict] = None) -> dict:
    if usage is None:
        return {"reported": False, **(route or {})}
    prompt_details = getattr(usage, "prompt_tokens_details", None)
    input_tokens = getattr(usage, "input_tokens", None) or getattr(usage, "prompt_tokens", None)
    output_tokens = getattr(usage, "output_tokens", None) or getattr(usage, "completion_tokens", None)
    cached_tokens = getattr(usage, "cache_read_input_tokens", None)
    if cached_tokens is None:
        prompt_details = getattr(usage, "prompt_tokens_details", None)
        cached_tokens = getattr(prompt_details, "cached_tokens", None) if prompt_details else None
    reported = any(value is not None for value in (input_tokens, output_tokens, getattr(usage, "total_tokens", None)))
    return {
        "reported": reported,
        "input_tokens": input_tokens,
        "output_tokens": output_tokens,
        "total_tokens": getattr(usage, "total_tokens", None),
        "cached_input_tokens": cached_tokens,
        **(route or {}),
    }


def chat_stream(
    system_prompt: str,
    user_message: str,
    history: Optional[List[dict]] = None,
    model: Optional[str] = None,
) -> Iterator[str]:
    """Stream a chat completion response chunk by chunk."""
    messages = [{"role": "system", "content": system_prompt}]

    if history:
        for msg in history:
            if msg["role"] in ("user", "assistant"):
                messages.append({"role": msg["role"], "content": msg["content"]})

    messages.append({"role": "user", "content": user_message})

    if settings.llm_api_mode == "responses":
        # Responses 流事件的兼容格式因网关而异；保留 NDJSON 上层协议，
        # 先以单块结果保证所有兼容网关都可用。
        result = chat(system_prompt, user_message, history, model)
        if result:
            yield result
        return

    if settings.llm_api_mode == "anthropic":
        result = chat(system_prompt, user_message, history, model)
        if result:
            yield result
        return

    stream, _ = model_gateway.invoke(model, lambda client, resolved_model: client.chat.completions.create(
        model=resolved_model,
        messages=messages,
        stream=True,
        **model_gateway.chat_completion_options(),
    ))

    for chunk in stream:
        if not chunk.choices:
            continue
        delta = chunk.choices[0].delta
        content = getattr(delta, "content", None)
        if content:
            yield content
