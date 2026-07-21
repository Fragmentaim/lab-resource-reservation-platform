# 检索回归评测

在这里维护少量、人工确认答案来源的检索样例：问题、允许访问的文档 ID，以及期望召回的 `chunk_id`。

质量门槛至少同时看三项：

- `Recall@k`：关键 chunk 是否被找回；
- `MRR@k`：第一个正确 chunk 排得是否足够靠前；
- ACL：任一返回 chunk 的 `document_id` 都必须属于本次允许访问范围。

`app.core.retrieval_eval` 是不依赖模型或 Qdrant 的评测内核，可由真实集成环境把 `rag_pipeline.retrieve_candidates` 注入后运行。当前 CI 覆盖指标计算、阈值失败与 ACL 泄漏失败；在导入脱敏知识库样例后，再将真实样例集接入该门槛，避免用合成数据伪造检索效果。
