# 文档索引

本分支提供预约系统、Java Agent 运行时和 Python RAG 服务。总览、系统架构及完整启动步骤见 [项目首页](../README.md)。预约后端展示版本位于同仓库的 [`main` 分支](https://github.com/Fragmentaim/lab-resource-reservation-platform/tree/main)。

## 模块与配置

| 入口 | 内容 |
| --- | --- |
| [`backend/`](../backend/) | 身份与权限、预约业务、文档元数据、Agent 工具编排和运行轨迹 |
| [`ai-service/`](../ai-service/) | Docling 解析、结构化切片、向量及关键词检索、重排、上下文摘要与 MCP Sidecar |
| [`sql/`](../sql/) | 初始化与增量升级脚本；使用前阅读脚本说明，旧数据库不要直接重新初始化 |
| [`compose.yaml`](../compose.yaml) | 本地平台与基础设施编排 |
| [根目录环境模板](../.env.example) / [AI 环境模板](../ai-service/.env.example) | Compose 与手动启动的配置入口 |
| [脚本索引](../scripts/README.md) | 启动、模型检查、检索评测和预约压测 |

## 预约设计与数据模型

- [需求分析](requirements-analysis.html)：预约平台需求与业务说明。
- [关系模式说明](reservation-relation-schema-simple.md)：预约核心实体与关系。
- [核心 ER 图](reservation-core-er-chen-style.svg)：预约核心实体的概念模型。
- [关系模式图](reservation-relation-schema-table.svg)：表结构与关系示意。

图中字段与当前实现可能因升级存在差异；实际数据库定义以当前分支的初始化和升级 SQL 为准。历史布局与导出文件保留在本目录，便于继续编辑原有设计稿。

## Agent 与 RAG 评测

- [Agent 评测协议](agent-evaluation.md)：会话、ACL、故障注入与真实运行轨迹的评测口径。
- [RAG 评测说明](rag-evaluation/README.md)：实验室知识库检索评测入口。
- [评测脚本与数据规范](../ai-service/evals/README.md)：Golden Set、证据审计、端到端检索与重排对照。
- [历史基线](rag-evaluation/baseline-20260719.md)：2026-07-19 的实验记录，不能视为当前分支的结果。
- [预约制度样例](rag-evaluation/lab-booking-policy.md)：实验室知识库样例材料。

单元测试、真实模型评测和预约压测分别反映不同能力。评测报告应记录分支、提交、模型与环境，使用隔离数据，并保留失败样例。
