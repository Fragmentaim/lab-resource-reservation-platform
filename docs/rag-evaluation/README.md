# RAG 评测基准

`lab-booking-policy.md` 是用于本地验收的受控知识源；上传并处理成功后，使用 `question-set.jsonl` 运行评测。

题集包含 30 条中文问题：27 条可回答的业务规则题和 3 条不可回答/隐私问题。每条样本标注了预期章节、关键术语和分类，用于同时观察回答正确性、引用正确性以及拒答能力。

## 使用方式

1. 管理员在“知识库管理”上传 `lab-booking-policy.md`，确认状态为 `READY`。
2. 启动后端和 AI 服务后，执行 `scripts/evaluate-rag.ps1`，结果默认写入 `D:\AI-Models\logs`，不会进入 Git。
3. 记录每次运行的模型、检索配置、召回条数、正确数、拒答数和延迟；只在同一题集、同一知识源下比较不同配置。

`scripts/evaluate-rag-retrieval.ps1` 是纯 RAG 评测入口：它直接访问 AI 服务并固定 `document_ids`，不会被预约工具路由截获。结果同时记录：

- `answerableSectionRecallAtK`：标注章节是否出现在前 K 条证据中；
- `answerableMRR`：标注章节的倒数排名均值；
- `vectorSourceCoverage` / `rerankCoverage`：是否实际经过向量召回和重排；
- 关键词命中、拒答率、平均及 P95 延迟。

先用五题做烟雾验证：

```powershell
.\scripts\evaluate-rag-retrieval.ps1 -MaxSamples 5
```

再运行完整 30 题基准：

```powershell
.\scripts\evaluate-rag-retrieval.ps1
```

当前题集是工程验收基准，不应将它的分数当成真实生产知识库的泛化能力。
