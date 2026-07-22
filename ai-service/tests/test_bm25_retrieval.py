from app.core.vectorstore import _bm25_tokens


def test_bm25_tokens_keep_rules_numbers_and_chinese_bigrams():
    tokens = _bm25_tokens("S42 自定义控制器不得设置广告位，预留 3m 电缆")

    assert "s42" in tokens
    assert "3m" in tokens
    assert "自定" in tokens
    assert "控制" in tokens
    assert "广告" in tokens
    assert "告位" in tokens
