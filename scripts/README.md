# 脚本索引

完整后端平台的环境准备与配置见 [项目首页](../README.md)。本目录的评测脚本按用途分组，运行结果和本地配置不作为源码提交。

| 用途 | 入口 | 运行前准备 |
| --- | --- | --- |
| 启动 Java 后端 | [`start-backend.ps1`](start-backend.ps1) | Java 17、Maven 和后端环境配置；需要 MQ 时设置 `SPRING_PROFILES_ACTIVE=mq` |
| 下载本地检索模型 | [`download-rag-models.ps1`](download-rag-models.ps1) | Python 环境、模型存储目录与可用网络 |
| 检查 Embedding | [`verify-rag-embedding.py`](verify-rag-embedding.py) | AI 服务依赖与 Embedding 模型配置 |
| 检查 Reranker | [`verify-rag-reranker.py`](verify-rag-reranker.py) | AI 服务依赖与重排模型配置 |
| 运行检索评测 | [`evaluate-rag.ps1`](evaluate-rag.ps1)、[`evaluate-rag-retrieval.ps1`](evaluate-rag-retrieval.ps1) | 隔离知识库、评测样例与检索服务 |
| 本地重排服务 | [`local-reranker-service/README.md`](local-reranker-service/README.md) | 该目录说明中的环境与模型 |
| 预约 A/B 与热点压测 | [`perf/README.md`](perf/README.md) | 独立压测数据库、Redis、RocketMQ 与预登录用户 |

Agent/RAG 的端到端评测工具位于 [`ai-service/evals/`](../ai-service/evals/README.md)，普通 Python 测试位于 `ai-service/tests/`，Java 测试位于 `backend/src/test/`。评测与压测可能调用模型、写入索引或创建业务数据，请使用各脚本说明中的隔离环境。
