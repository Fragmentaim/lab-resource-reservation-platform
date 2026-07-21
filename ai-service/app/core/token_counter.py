from functools import lru_cache


@lru_cache(maxsize=1)
def _encoding():
    try:
        import tiktoken
        return tiktoken.get_encoding("cl100k_base")
    except Exception:
        return None


def count_tokens(text: str | None) -> int:
    if not text:
        return 0

    encoding = _encoding()
    if encoding is not None:
        try:
            return len(encoding.encode(text))
        except Exception:
            pass

    chinese_chars = sum(1 for char in text if "\u4e00" <= char <= "\u9fff")
    other_chars = max(0, len(text) - chinese_chars)
    return max(1, int(chinese_chars * 1.2 + other_chars / 4))


def truncate_by_tokens(text: str | None, max_tokens: int) -> tuple[str, int, bool]:
    if not text or max_tokens <= 0:
        return "", 0, bool(text)

    tokens = count_tokens(text)
    if tokens <= max_tokens:
        return text, tokens, False

    ratio = max_tokens / max(tokens, 1)
    keep_chars = max(80, int(len(text) * ratio * 0.92))
    truncated = text[:keep_chars].rstrip() + "\n[已按 token 预算截断]"
    return truncated, count_tokens(truncated), True
