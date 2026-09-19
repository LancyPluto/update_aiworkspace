# Agent 运行自动恢复

后端启动与定时扫描会接管租约过期的 `RUNNING` / `CREATED` run。原 run ID、LangGraph thread ID、工具调用键和任务请求键均保留。`WAITING_USER_CONFIRMATION` 不参与扫描；确认通知必须匹配数据库保存的具体调用批准记录。

## 部署

1. 先应用已有 checkpoint / 工具幂等 / 租约迁移及 `134_agent_run_safe_recovery.sql`，用 `deploy/scripts/apply_sql_migrations.sh` 校验迁移 checksum。
2. 在恢复开关关闭时部署后端与 agent-service。安装更新后的 Python requirements；原生恢复使用 LangGraph 1.x、checkpoint 4.x 的 interrupt ID 和 pending writes 接口。
3. 排空并停止旧 agent-service 实例，确保旧进程不再执行。首次从旧版本升级时，可在这个维护窗口把遗留 `agent_run_execution_leases.lease_expires_at` 设置为数据库 `CURRENT_TIMESTAMP`；旧版本租约使用应用时钟，新版本统一使用数据库时钟，不能在旧进程仍运行时批量失效租约。
4. 给后端设置 `AGENT_AUTO_RECOVERY_ENABLED=true` 后重启。关闭开关只停止扫描；带执行凭证的请求仍校验所有权，不退回“超时即失败”。新版本运行时始终依赖新迁移和内部接口。

配置默认值：扫描 60000 ms、批次 20、租约 60 秒、续租 20 秒、无租约记录宽限 15 分钟、累计最多 5 次恢复尝试，失败退避 1/2/4/8 分钟。可用 `AGENT_AUTO_RECOVERY_MAX_ATTEMPTS`、`AGENT_STALE_RUN_AFTER_MINUTES`、`AGENT_RECOVERY_SCAN_INTERVAL_MS`、`AGENT_RECOVERY_BATCH_SIZE` 调整扫描策略。

## 行为与边界

- 接管在 run 行锁内重查状态并登记次数；恢复通知必须一次性领取凭证，重复通知不启动第二个执行器。内部写入在同一事务中校验 owner 和未过期租约。
- 续租失败停止执行，不失败 run、不取消原任务。恢复时查询原任务；普通轮询不会消耗恢复次数。提交结果不确定时只核对，临时故障持久化退避。
- checkpoint 和 pending writes 恢复原图，预算、工具计数、重试信息及工具展开状态另外持久化。已完成图只补齐收尾；最终状态、消息和积分结算复用已有幂等逻辑。
- 对没有执行证据的新 run 允许启动。已有执行证据却缺少可验证 checkpoint／runtime 状态、版本不兼容等情况，以 `AGENT_RECOVERY_UNSAFE` 收尾。旧版本中断的运行不能凭空补出缺失的恢复状态。
- 记忆写入和文件记录使用操作结果表去重。若自动记忆整理在中途退出且无法证明完整结果，停止自动重放并报告不可安全恢复；不会猜测成功或重复执行未知操作。
- 恢复失败或次数耗尽保留外部任务关联，不追加任务取消。外部供应商／worker 的恢复仍归原任务系统管理。

## 诊断与验证

查看 `agent_run_recovery` 的 attempts、next_attempt_at、last_error 和 runtime_json。执行凭证不写入公开事件。事件包括 `run.recovery_scheduled`、`run.recovery_started`、`run.recovery_task_reused`、`run.recovery_retry`、`run.recovery_completed`。后端指标 `agent.recovery.attempts`、`agent.recovery.exhausted` 与 agent-service 原有运行指标的 recover entrypoint / deferred / lease_lost 状态用于观察接管和耗时。

Python：`python -m pytest tests/test_restart_recovery.py tests/test_backend_checkpointer.py tests/test_agent_runtime_graph_entrypoint.py tests/test_agent_graph_engine.py tests/test_backend_tool_bridge.py`（在 agent-service 目录运行）。包含真正的子进程退出，退出点位于任务提交之后、绑定与下一 checkpoint 之前。

Java：使用项目要求的 JDK 17 执行 `mvn -f backend/pom.xml -Dtest=AgentRecoveryConcurrencyTest,AgentApiTest test`。并发测试默认使用 H2；可设置 `RECOVERY_TEST_JDBC_URL`、`RECOVERY_TEST_DB_USER`、`RECOVERY_TEST_DB_PASSWORD` 在隔离 MySQL 数据库运行。该测试会重建测试表，只能指向专用测试库。

本次验证：agent-service 全套 297 项通过；后端 Agent API、工作流委派与恢复测试共 76 项通过；恢复测试另在 MySQL 8.0 执行 5 项通过。新迁移在空表和含历史 run 的数据库验证通过，checksum 复核通过，历史等待确认状态保持不变。
