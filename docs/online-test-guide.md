# 本地与上线测试指南

本指南只覆盖当前保留的主链路：AI 工具、Agent、算力账户、模型配置、RabbitMQ、Redis、Nginx 可选入口。模型渠道套餐、订单、Model Gateway、模型节点池已从当前方向中移除。

## 1. 启动方式

```powershell
docker compose -f deploy/docker-compose.yml up -d --build
```

可选 Nginx 本地入口：

```powershell
docker compose -f deploy/docker-compose.yml -f deploy/docker-compose.nginx.yml up -d --build
```

## 2. 访问入口

| 服务 | 地址 |
| --- | --- |
| 用户端 | `http://127.0.0.1:5173` |
| 管理端 | `http://127.0.0.1:5174` |
| 后端 API | `http://127.0.0.1:8080` |
| Agent Service | `http://127.0.0.1:8090` |
| RabbitMQ 控制台 | `http://127.0.0.1:15672` |
| 可选 Nginx | `http://127.0.0.1:8088` |

## 3. 环境变量

复制 `.env.example` 为 `.env` 后按本机情况调整。真实密钥、模型 API Key、短信密钥不要提交到 Git。

当前保留的核心配置包括：

- MySQL：`MYSQL_HOST`、`MYSQL_PORT`、`MYSQL_DATABASE`、`MYSQL_USERNAME`、`MYSQL_PASSWORD`
- Redis：`REDIS_HOST`、`REDIS_PORT`、`REDIS_PASSWORD`、`REDIS_DATABASE`
- RabbitMQ：`RABBITMQ_USERNAME`、`RABBITMQ_PASSWORD`、`RABBITMQ_TASK_QUEUE`
- 后端安全：`JWT_SECRET`、`INTERNAL_API_TOKEN`
- Agent：`AGENT_SERVICE_BASE_URL`、`AGENT_DEFAULT_CREDIT_BUDGET`

## 4. 数据库初始化

基础表和 seed SQL 位于 `sql/` 目录。当前不再初始化模型渠道套餐、订单、模型节点池相关表。

保留的关键 SQL：

- `sql/001_init_v1.sql`
- `sql/007_agent_credit_logs.sql`
- `sql/009_agent_model_configs.sql`
- Agent、工具和种子工具相关 SQL

## 5. 测试账号

| 类型 | 账号 | 密码 |
| --- | --- | --- |
| 管理员 | `admin` | `123456` |
| 普通用户 | `user1` | `123456` |

## 6. 冒烟测试

后端健康检查：

```powershell
Invoke-RestMethod http://127.0.0.1:8080/api/health
```

用户登录并查看算力：

```powershell
$login = Invoke-RestMethod -Method Post -Uri http://127.0.0.1:8080/api/v1/auth/login -ContentType "application/json" -Body '{"account":"user1","password":"123456"}'
$token = $login.data.accessToken
Invoke-RestMethod -Uri http://127.0.0.1:8080/api/v1/credits/account -Headers @{ Authorization = "Bearer $token" }
Invoke-RestMethod -Uri http://127.0.0.1:8080/api/v1/credits/logs -Headers @{ Authorization = "Bearer $token" }
```

管理端模型配置：

```powershell
$admin = Invoke-RestMethod -Method Post -Uri http://127.0.0.1:8080/api/admin/v1/auth/login -ContentType "application/json" -Body '{"account":"admin","password":"123456"}'
$adminToken = $admin.data.accessToken
Invoke-RestMethod -Uri http://127.0.0.1:8080/api/admin/v1/agent-model-configs -Headers @{ Authorization = "Bearer $adminToken" }
```

AI 工具执行仍统一消耗算力，不再校验模型渠道套餐或节点池。

## 7. RabbitMQ 和 Redis 验证

RabbitMQ 用于可靠任务队列，Redis 用于缓存、限流和短期状态。验证时重点关注：

- 任务发布后能进入 RabbitMQ 队列。
- worker 重启后任务不会丢失。
- 失败任务能进入重试或死信路径。
- Redis 可正常连接，Agent 限流和登录状态不报错。

## 8. 常见问题

| 问题 | 处理方式 |
| --- | --- |
| 前端无法访问后端 | 检查 `VITE_API_BASE_URL`、`NEXT_PUBLIC_API_BASE_URL` 和 Docker 端口映射。 |
| 用户端没有模型工作台入口 | 当前方向已移除模型渠道工作台，这是预期行为。 |
| 没有套餐或订单页面 | 当前商业化统一回到算力账户，套餐/订单后续如需要会按“算力充值订单”单独设计。 |
| 模型调用失败 | 到管理端“系统配置/大模型接入”检查模型配置、Base URL、API Key、协议类型和超时时间。 |
| RabbitMQ 控制台打不开 | 检查 `rabbitmq` 容器状态和 `15672` 端口占用。 |

## 9. 回滚建议

如果上线测试发现问题，优先回滚应用镜像，不要回滚数据库数据。当前删除的是模型渠道相关开发表和入口，算力、任务、工具、模型配置主链路应保持可用。
