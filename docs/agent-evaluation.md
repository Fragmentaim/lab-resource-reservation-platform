# Agent Evaluation Protocol v1

本协议评测的是 Java Agent 的真实执行结果，而非仅评估模型是否生成了一个工具名。所有结果必须从 `AgentRun`、`AgentStep`、工具输出和向量检索返回的文档 ID 聚合产生。

## 固定套件

| 套件 | 数量 | 核心检查 |
| --- | ---: | --- |
| Agent 任务 | 120 | 工具选择、参数、业务结果、多步完成、误调用、调用步数 |
| 多轮会话 | 30 组 × 8 轮 | 约束保留、指代解析、摘要压缩前后任务准确率 |
| ACL 检索 | 50 | 原始返回 document ID、合法召回、越权拦截、错误拒绝 |
| 故障注入 | 24 | 超时/429/500/参数错误/工具超时/重复调用/MQ 重投/重启恢复 |

`AgentEvaluationFixtures`、`ConversationEvaluationFixtures`、`AclEvaluationFixtures` 和 `FaultEvaluationFixtures` 是版本化、确定性的测试输入。修改任何输入都必须升级套件版本，不能删掉失败样例后直接比较指标。

## 如何执行正式评测

1. 启动隔离的 MySQL、Redis、Qdrant 和 RocketMQ；导入专用预约、资源及文档权限种子数据。禁止复用生产或个人历史会话。
2. 启动 Java 与 FastAPI，显式记录模型、API 版本、测试时间、地区和网络环境。
3. 仅在隔离环境增加 `app.knowledge.agent-evaluation.capture-arguments=true`。该开关会将截断且过滤 `password/token/secret/authorization` 后的参数快照写入 `AgentStep.detail`，默认关闭。
4. 每个任务执行后读取其 `AgentRun` 与 `AgentStep`；`AgentRunTraceAdapter` 将其还原为评测 trace。任务完成必须同时满足最终状态和 `expectedBusinessResult`，不能仅因工具名称命中而成功。
5. ACL 套件必须记录 Qdrant/关键词检索实际返回的 `document_id`。受限文档即使被最终回答隐藏，也按泄漏失败。
6. 故障套件每种故障执行三次；重复副作用必须为 0，失败 Run/Step 必须可定位。
7. 性能套件分别在并发 10、30、50 下执行，输出总耗时 P50/P95/P99、模型首次决策、检索、工具、最终生成、Token、缓存 Token 和每成功任务成本。

## 当前可验证状态

本仓库已具备评测数据模型、固定套件、评分器、从 Run/Step 聚合 Trace 的适配器，以及针对这些规则的单元测试。它们保证口径与数据规模正确，但**不代表已经产生真实模型成功率**。

正式报告只能写入实际运行生成的 trace；在未运行隔离环境前，不得把任何示例数据或单测通过数写进简历。

## 规划器契约冒烟（不等同正式评测）

`AgentEvaluationFixtureExporter` 会从 Java 的固定套件导出 JSON；
`ai-service/evals/run_agent_planner_benchmark.py` 再把这些输入交给运行中的
FastAPI `/tool-calling/round`。模型调用是真实的，工具输出是确定性模拟值，目的仅是快速定位
Function Calling 的工具选择、参数格式和多轮工具衔接问题。

```powershell
cd backend
& 'D:\tools\apache-maven-3.9.11\bin\mvn.cmd' -q exec:java `
  '-Dexec.mainClass=com.fragment.labbooking.knowledge.evaluation.AgentEvaluationFixtureExporter' `
  '-Dexec.args=D:\agent-eval-runtime\agent-suite-v1.json'

cd ..
& '.\ai-service\.venv\Scripts\python.exe' .\ai-service\evals\run_agent_planner_benchmark.py `
  --suite D:\agent-eval-runtime\agent-suite-v1.json `
  --output D:\agent-eval-runtime\planner-contract-v1.json `
  --endpoint http://127.0.0.1:8005/api/v1/ai/tool-calling/round `
  --token $env:AI_SERVICE_TOKEN --model glm-5.1 --concurrency 10
```

不要把 API Key 写入命令、报告或仓库。先由操作者在当前进程环境中设置 `LLM_API_KEY`、
`LLM_BASE_URL`、`AI_SERVICE_TOKEN` 后启动隔离端口的 FastAPI。输出中的
`benchmark_type=real_model_planner_contract` 明确表示它**不是**真实预约成功率、ACL 数据库
泄漏率或端到端任务成功率；这三项仍必须通过上面的正式隔离环境和 `AgentRun/Step` trace 生成。
