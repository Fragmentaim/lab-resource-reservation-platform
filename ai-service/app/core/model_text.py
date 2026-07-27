import re


_LEADING_THINK_BLOCK = re.compile(r"^\s*<think>[\s\S]*?</think>\s*", re.I)


def visible_model_text(raw: str) -> str:
    """Remove a provider-serialized reasoning envelope from visible model text."""
    return _LEADING_THINK_BLOCK.sub("", raw or "", count=1).strip()
