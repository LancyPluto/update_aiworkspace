# LangGraph 运行闭环开发与验收文档

> 面向开发与测试。本文记录当前源码已经落地的运行闭环契约和验证事实；未验收项目明确标注，不能据此视为生产发布结论。

## 1. 边界与运行模型

- Java Backend 是数据库、任务、积分、权限、事件和审计的唯一事实源；Python `agent-service` 仅通过内部 API 访问它们。
- 每个 run 使用固定 `thread_id = agent-run:{runId}`。图状态由 LangGraph 原生 checkpointer 保存，业务暂停摘要保留在 `agent_runs.graph_checkpoint_json`，两者不得混写。
- 正常调用路径为：模型流式输出 -> LangGraph `StateGraph` -> `ToolOrchestrator` -> `BackendToolBridge` -> Backend 任务链路。模型不得直接访问数据库或 Worker。
- 需确认的工具在图节点中调用 `interrupt(...)`；确认接口将 run 从 `WAITING_USER_CONFIRMATION` CAS 到 `RUNNING` 后，agent-service 以同一线程的 `Command(resume=...)` 继续图。

## 2. 持久化与内部接口

### 2.1 Native checkpoint

`agent_langgraph_checkpoints` 的一条记录表示 `(run_id, thread_id, checkpoint_ns, checkpoint_id)` 的历史版本；最新 head 由 `id DESC` 查询，而不是覆盖同一行。`checkpoint_json` 保存 LangGraph serde 类型与 Base64 payload，`metadata_json` 保存 checkpoint metadata。

`agent_langgraph_checkpoint_writes` 以 `(run_id, thread_id, checkpoint_ns, checkpoint_id, task_id, write_index)` 独立保存 pending writes。每个值保存 `value_type + value_base64`，禁止将对象通过 `default=str` 降级为字符串。

内部 API：

| 目的 | 方法与路径 |
|---|---|
| 保存 / 读取 checkpoint | `PUT` / `GET /api/internal/v1/agent/runs/{runId}/langgraph-checkpoint` |
| 保存 pending writes | `PUT /api/internal/v1/agent/runs/{runId}/langgraph-checkpoint-writes` |
| 历史查询 | `POST /api/internal/v1/agent/runs/{runId}/langgraph-checkpoints/search` |
| 删除线程 checkpoint | `DELETE /api/internal/v1/agent/runs/{runId}/langgraph-checkpoint` |
| 获取 / 续租 / 释放执行锁 | `POST .../execution-lease/acquire`、`POST .../renew`、`DELETE .../execution-lease` |

历史查询支持 namespace、`beforeCheckpointId`、`limit` 和 metadata 精确过滤。Python `BackendCheckpointSaver` 缺少这些 API 或序列化失败时必须失败，不能静默跳过持久化。

### 2.2 执行锁、幂等与保留

- `agent_run_execution_leases` 为每个 run 保存 owner token 和到期时间。AgentRuntime 在启动与确认恢复前申请锁，执行中续租，最终释放；未获得锁的重复投递正常跳过，不标记 run 失败。
- `agent_tool_calls.idempotency_key` 使用 `agent-run:{runId}:call:{LangGraphCallId}`。同 run、同 key 返回既有工具调用，避免重复创建任务和重复副作用。
- `AgentLangGraphCheckpointRetentionScheduler` 默认每天 `03:35` 运行，删除完成、失败、取消或超时且超过 30 天的 Native checkpoint 与 pending writes；默认批量大小为 500。业务 run、事件和审计记录不受影响。

## 3. SQL 迁移

按顺序执行：

1. `sql/130_langgraph_checkpoints.sql`：新环境创建 checkpoint 与 writes 表。
2. `sql/131_migrate_langgraph_checkpoint_history.sql`：旧单 head 表添加 namespace、替换唯一键，并创建 writes 表。
3. `sql/132_agent_tool_call_idempotency.sql`：增加工具调用幂等键及唯一索引。
4. `sql/133_agent_run_execution_leases.sql`：创建执行 lease 表。

在生产执行前备份数据库并在预发验证。SQL 只做向前兼容的结构升级；不自动反向迁移。已执行的迁移文件不得原地修改，修复应新增编号脚本。

## 4. 本地验证

```bash
cd agent-service
pytest -q

cd ../backend
./mvnw -q test
```

当前已运行结果：Python 全量 `283 passed`；Java 全量 `./mvnw -q test` 通过（Surefire 无 failure/error）。启动本地服务后确认：

```bash
curl -fsS http://127.0.0.1:8080/actuator/health
curl -fsS http://127.0.0.1:8090/health
```

创建专用的可丢弃 run 后执行真实 Backend/MySQL smoke：

```bash
BACKEND_INTERNAL_BASE_URL=http://127.0.0.1:8080 \
INTERNAL_API_TOKEN=local-internal-token \
LANGGRAPH_E2E_RUN_ID=<runId> \
python scripts/e2e_langgraph_checkpoint.py
```

预期输出：

```text
checkpoint_history=ok pending_writes=ok namespace=ok
execution_lease=ok cleanup=ok
```

脚本会写入后清理该 run 的 Native checkpoint，只能使用临时、非收费、无人正在使用的 run。创建 run 可调用 `POST /api/v1/agent/sessions/{sessionId}/messages`，响应的 `data.runId` 即为脚本参数。

## 5. 故障定位

| 现象 | 首先检查 |
|---|---|
| checkpoint 不存在或恢复不到历史版本 | `130/131` 是否执行、`thread_id` 与 namespace、`BackendCheckpointSaver` 的内部 API 响应 |
| pending writes 丢失 | writes 表唯一键、`value_type/value_base64`、对应 checkpoint ID 是否一致 |
| 重复确认仍执行 | lease acquire 返回值、run 状态 CAS、`agent_run_execution_leases` 到期时间 |
| 重复创建任务 | `agent_tool_calls.idempotency_key`、相同 call ID、下游 task 的 client request ID |
| 工具参数异常 | `chat_stream_parts` 的工具 ID、名称与 JSON 参数分片；不完整参数必须拒绝执行 |
| 迁移失败 | INFORMATION_SCHEMA 条件检查、现有索引名、目标 MySQL 版本和发布顺序 |
| 重复最终消息 | `message.delta` 与 `message.completed` 事件序列、`_emit_answer` 调用次数 |

## 6. 验收状态

### 已验证

- Native checkpoint 历史、namespace、typed pending writes 的 Python saver 测试。
- 执行 lease 的真实 Backend/MySQL smoke。
- 真实 E2E 输出已确认 checkpoint history、pending writes、namespace、lease 互斥和清理。
- Python 全量测试通过：`283 passed`。
- 在线确认图引擎测试：`interrupt -> Command(resume)` 使用同一 `thread_id`，暂停前模型不重复请求、工具只创建和执行一次，恢复后清理 checkpoint 并完成 run。
- 最终消息测试：允许多条 `message.delta`，每个正常 run 恰好一条 `message.completed`；streaming answer snapshot 不再追加第二条完成事件。
- 取消边界测试：运行前取消、确认等待期间取消、第三方工具返回后取消均不再完成 run；工具返回后的取消不会发布成功工具事件。
- retention scheduler 单元测试：writes 先于 checkpoint 批量删除，重复调度无错误，非终态 run 不进入删除批次。
- 确认幂等与 CAS 测试：重复确认只通知一次 agent-service；相同 LangGraph call ID 返回同一 `agent_tool_calls`，不同 call ID 可独立创建。

### 尚未完成验收

- Backend/MySQL 真实在线确认端到端：当前已有 MockMvc 的状态 CAS、确认幂等和 tool-call 幂等覆盖；仍需在专用非收费替身 run 上跑完整 `interrupt -> confirm -> resume` HTTP smoke。
- 取消与确认并发的真实 HTTP/agent-service 竞态：单元边界已覆盖，仍需专用替身 run 验证积分、事件和 lease 的最终状态。
- 服务重启后的恢复：本轮不作为已完成能力；后续需验证暂停后换实例再确认。
- retention scheduler 的真实定时触发与超过 30 天数据清理；本轮已完成不等待 cron 的单元验证。
