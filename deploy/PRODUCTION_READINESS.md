# 生产发布前置门禁

这组脚本的目标很简单：配置不完整、历史数据有异常、备份不可恢复时，不让 CD 继续发布。
脚本不会把密码或告警地址写进日志。

## 一次性准备

1. 在独立 OSS 桶启用版本控制、服务端加密和生命周期策略。备份桶不能复用应用图片/视频桶。
2. CD Runner 为每次发布生成临时 MySQL 只读账号，远程创建后只授予业务库的 `SELECT`，迁移前后检查结束即删除。
3. RabbitMQ 和备份密码优先复用服务器现有配置，缺失时只生成一次并以 `600` 权限持久化；这些值不写入 Git 或发布日志。

服务器 `deploy/.env`：

```env
BACKUP_ENCRYPTION_PASSWORD=至少32位随机值
BACKUP_OSS_URI=oss://独立备份桶/mysql/full
```

服务器根 `.env`：

```env
WORKFLOW_RUNTIME_ENABLED=true
WORKFLOW_RUNTIME_EXECUTION_ENABLED=true
WORKFLOW_RUNTIME_REAL_BILLING_ENABLED=true
```

临时只读账号权限验收必须满足：`SHOW GRANTS` 只包含 `SELECT` 和基础 `USAGE`，不能包含
`INSERT`、`UPDATE`、`DELETE`、DDL 或管理权限；CD 无论成功或失败都必须删除该账号。

## 每次发布自动执行

CD 会依次执行：

1. `verify_production_environment.sh`：检查 RabbitMQ 非默认账号、异地加密备份、临时只读账号和工作流开关。
2. `production_readonly_preflight.sh historical`：迁移前只读检查历史幂等键重复。
3. `backup_mysql.sh`：生成 AES-256 加密文件和 SHA-256 清单，上传 OSS 后确认两个远端对象都存在。
4. `apply_sql_migrations.sh`：执行数据库迁移。
5. `production_readonly_preflight.sh post-migration`：只读检查迁移登记、孤儿账单和账单绑定缺失。

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

## 统一算力计费和紧急停用

工作流按统一算力额度执行单次和用户日限额。供应商成本与币种字段仅保留为可选审计数据，
缺失或币种异常不会阻断工作流；相关异常通过 Prometheus 规则显示在 Grafana Alerting 页面。

需要人工紧急停用时，把 `WORKFLOW_RUNTIME_EXECUTION_ENABLED=false` 写入服务器环境并重新创建
backend 容器。恢复前必须先确认成本数据完整、告警已送达，并从小范围白名单重新开放。

## 上线前人工证据

- 生产环境配置预检通过。
- 迁移前、迁移后只读报告均为 `status=PASS`。
- OSS 中加密备份和 manifest 均可读取，SHA-256 与本地一致。
- 最近一次隔离恢复演练为 `status=SUCCESS`，实测 RTO 不超过目标。
- 在 staging 触发一次供应商成本审计异常，确认 Grafana Alerting 页面出现对应规则状态。
