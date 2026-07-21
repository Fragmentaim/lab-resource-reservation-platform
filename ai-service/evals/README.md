# 检索回归评测

在这里维护少量、人工确认答案来源的检索样例：问题、允许访问的文档 ID，以及期望召回的 `chunk_id`。

质量门槛至少同时看三项：

- `Recall@k`：关键 chunk 是否被找回；
- `MRR@k`：第一个正确 chunk 排得是否足够靠前；
- ACL：任一返回 chunk 的 `document_id` 都必须属于本次允许访问范围。

`app.core.retrieval_eval` 是不依赖模型或 Qdrant 的评测内核，可由真实集成环境把 `rag_pipeline.retrieve_candidates` 注入后运行。当前 CI 覆盖指标计算、阈值失败与 ACL 泄漏失败；在导入脱敏知识库样例后，再将真实样例集接入该门槛，避免用合成数据伪造检索效果。

## RoboMaster 端到端 Benchmark

端到端评测脚本刻意复用真实的解析、切片、Embedding、Qdrant 检索、RRF 融合和上下文打包代码；不使用额外的模拟检索器。

流程如下：

1. `build_robomaster_golden.py` 将候选题绑定到文档名、页码和 `content_hash`。运行时 Chunk ID 含文档版本，不能作为跨重建的真值 ID。
2. `audit_golden_evidence.py` 用独立模型核验证据是否足以推出标注答案；只有全部审计模型通过的题才进入最终 Golden Set。
3. `index_robomaster_benchmark.py` 通过应用的文档处理函数建立隔离索引。务必设置独立的 `QDRANT_COLLECTION` 与 `QDRANT_LOCAL_PATH`，不要污染业务知识库。
4. `run_rag_benchmark.py` 比较 `first_stage` 与 `reranked`，输出逐题检索记录、回答记录及汇总报告。

检索报告包含 `Recall@1/3/5`、`HitRate@1/3/5`、`MRR@1/3/5` 和 P50/P95 延迟。启用 `--answer-mode` 时，还会记录：

- `answer_accuracy`：答案选项正确率；
- `citation_hit_rate`：模型引用是否命中 Golden 证据；
- `grounded_accuracy`：答案正确且引用命中；
- 上下文证据 Token 与模型耗时。

示例（变量均应指向隔离评测环境）：

```powershell
$env:QDRANT_COLLECTION = "robomaster_benchmark_v1"
$env:QDRANT_LOCAL_PATH = "D:\RoboMaster_RAG\qdrant"
$env:QDRANT_VECTOR_SIZE = "1024"
$env:USE_LOCAL_EMBEDDING = "true"
$env:LOCAL_EMBEDDING_MODEL = "D:\AI-Models\models\Qwen3-Embedding-0.6B"

python evals\run_rag_benchmark.py `
  --golden D:\RoboMaster_RAG\robomaster_golden_v1.jsonl `
  --document-id D1=9001 --document-id D2=9002 --document-id D3=9003 `
  --output-dir D:\RoboMaster_RAG\benchmark-output
```

不要把“模型交叉答对”直接当作 Golden 标注。若证据审计显示原文不完整、解析遗漏或题目来自缺失附件，应从量化集排除并单独维护为语料缺口样例。

重排也必须做同集对照：保留 `first_stage` 与 `reranked` 两组结果，只有 `reranked` 在 Recall/MRR 上稳定增益时才作为线上默认；否则保留首阶段排序，避免为了“接入 Rerank”牺牲检索质量和延迟。
