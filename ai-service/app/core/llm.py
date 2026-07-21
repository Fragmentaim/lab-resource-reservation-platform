from app.config import settings
from app.core import model_gateway
from typing import Iterator, List, Optional
get_client = model_gateway.get_client


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
    client = get_client()
    messages = [{"role": "system", "content": system_prompt}]

    if history:
        for msg in history:
            if msg["role"] in ("user", "assistant"):
                messages.append({"role": msg["role"], "content": msg["content"]})

    messages.append({"role": "user", "content": user_message})

    if settings.llm_api_mode == "responses":
        response = client.responses.create(
            model=model_gateway.chat_capability(model).model,
            instructions=system_prompt,
            input=[
                {
                    "role": message["role"],
                    "content": [{"type": "input_text", "text": message["content"]}],
                }
                for message in messages
                if message["role"] != "system"
            ],
        )
        return response.output_text or "", _usage_snapshot(getattr(response, "usage", None))

    response = client.chat.completions.create(
        model=model_gateway.chat_capability(model).model,
        messages=messages,
        temperature=0.1,
    )
    return response.choices[0].message.content or "", _usage_snapshot(getattr(response, "usage", None))


def _usage_snapshot(usage) -> dict:
    if usage is None:
        return {"reported": False}
    prompt_details = getattr(usage, "prompt_tokens_details", None)
    return {
        "reported": True,
        "input_tokens": getattr(usage, "input_tokens", None) or getattr(usage, "prompt_tokens", None),
        "output_tokens": getattr(usage, "output_tokens", None) or getattr(usage, "completion_tokens", None),
        "total_tokens": getattr(usage, "total_tokens", None),
        "cached_input_tokens": getattr(prompt_details, "cached_tokens", None) if prompt_details else None,
    }


def chat_stream(
    system_prompt: str,
    user_message: str,
    history: Optional[List[dict]] = None,
    model: Optional[str] = None,
) -> Iterator[str]:
    """Stream a chat completion response chunk by chunk."""
    client = get_client()
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

    stream = client.chat.completions.create(
        model=model_gateway.chat_capability(model).model,
        messages=messages,
        temperature=0.1,
        stream=True,
    )

    for chunk in stream:
        if not chunk.choices:
            continue
        delta = chunk.choices[0].delta
        content = getattr(delta, "content", None)
        if content:
            yield content
