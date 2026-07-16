# 生产发布前置门禁

这组脚本的目标很简单：配置不完整、历史数据有异常、备份不可恢复时，不让 CD 继续发布。
脚本不会把密码或告警地址写进日志。

## 一次性准备

1. 在独立 OSS 桶启用版本控制、服务端加密和生命周期策略。备份桶不能复用应用图片/视频桶。
2. 在 MySQL 创建只读预检账号，只授予业务库的 `SELECT`。不要使用 `root`，也不要授予写入或管理权限。
3. 在服务器 `deploy/.env` 配置备份和只读账号，在服务器根 `.env` 配置工作流运行参数。
真实值只保存在服务器密钥配置中。这样只读账号和备份密钥不会被 `env_file` 注入应用容器。

服务器 `deploy/.env`：

```env
BACKUP_ENCRYPTION_PASSWORD=至少32位随机值
BACKUP_OSS_URI=oss://独立备份桶/mysql/full
PRODUCTION_PREFLIGHT_MYSQL_USER=release_preflight_readonly
PRODUCTION_PREFLIGHT_MYSQL_PASSWORD=随机密码
```

服务器根 `.env`：

```env
WORKFLOW_RUNTIME_MAX_PROVIDER_DAILY_COST_CNY=审批后的每日人民币上限
WORKFLOW_RUNTIME_COST_ALERT_WEBHOOK_URL=https://告警系统地址
WORKFLOW_RUNTIME_EXECUTION_ENABLED=false
```

只读账号可由数据库管理员按公司账号规范创建。权限验收必须满足：`SHOW GRANTS` 只包含 `SELECT`
和基础 `USAGE`，不能包含 `INSERT`、`UPDATE`、`DELETE`、DDL 或管理权限。

## 每次发布自动执行

CD 会依次执行：

1. `verify_production_environment.sh`：检查 RabbitMQ 非默认账号、异地加密备份、只读账号、供应商日成本上限和告警地址。
2. `production_readonly_preflight.sh historical`：迁移前只读检查历史幂等键重复。
3. `backup_mysql.sh`：生成 AES-256 加密文件和 SHA-256 清单，上传 OSS 后确认两个远端对象都存在。
4. `apply_sql_migrations.sh`：执行数据库迁移。
5. `production_readonly_preflight.sh post-migration`：只读检查迁移登记、孤儿账单、真实成本缺失和账单绑定缺失。

任一步失败，发布立即停止。只读预检报告位于 `deploy/logs/`，权限为 `600`。

## 恢复演练

至少每月从 OSS 取一组 `.sql.gz.enc` 和 `.manifest`，在隔离数据库执行：

```bash
export TARGET_DB=ai_supermarket_restore
export BACKUP_FILE=/secure/path/ai_supermarket_v1_YYYYMMDDTHHMMSSZ.sql.gz.enc
export BACKUP_MANIFEST=/secure/path/ai_supermarket_v1_YYYYMMDDTHHMMSSZ.manifest
export BACKUP_ENCRYPTION_PASSWORD=从密钥管理器读取
bash deploy/scripts/restore_mysql_to_staging.sh
```

演练报告默认写到 `deploy/backup/restore-drills/`，包含校验和、开始/结束时间、耗时和结果，
不包含数据库密码。工单中还应记录操作者、代码版本、备份日期、恢复后的抽样核对结果和最终清理时间。

## 成本上限和紧急停用

后端在创建新工作流 run 时检查当天供应商实际成本。达到
`WORKFLOW_RUNTIME_MAX_PROVIDER_DAILY_COST_CNY` 后会拒绝新的 run，并向
`WORKFLOW_RUNTIME_COST_ALERT_WEBHOOK_URL` 发送告警；已经在执行的 run 继续按原有终态逻辑收口。

需要人工紧急停用时，把 `WORKFLOW_RUNTIME_EXECUTION_ENABLED=false` 写入服务器环境并重新创建
backend 容器。恢复前必须先确认成本数据完整、告警已送达，并从小范围白名单重新开放。

## 上线前人工证据

- 生产环境配置预检通过。
- 迁移前、迁移后只读报告均为 `status=PASS`。
- OSS 中加密备份和 manifest 均可读取，SHA-256 与本地一致。
- 最近一次隔离恢复演练为 `status=SUCCESS`，实测 RTO 不超过目标。
- 在 staging 触发一次成本告警，值班人员确认实际收到；不要只验证 URL 非空。
- 将供应商控制台日限额设为应用日限额的第二道保护，并验证供应商侧告警。
