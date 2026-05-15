# 上线测试文档

本文档用于本地预发、上线前冒烟和问题回滚演练。所有命令默认在仓库根目录 `C:\ai\ai-tool-market` 执行。

## 1. 启动方式

基础 Docker 启动：

```powershell
docker compose -f deploy/docker-compose.yml up -d
```

查看服务状态：

```powershell
docker compose -f deploy/docker-compose.yml ps
```

首次启动会拉取镜像，并在容器内安装 Maven、npm、pip 依赖，耗时会比较久。后续只需要重启：

```powershell
docker compose -f deploy/docker-compose.yml restart
```

如果本机残留旧容器，可以移除孤儿容器后启动：

```powershell
docker compose -f deploy/docker-compose.yml up -d --remove-orphans
```

## 2. 环境变量示例

根目录已有 `.env.example`，上线测试建议复制为 `.env` 后按环境替换密钥：

```powershell
Copy-Item .env.example .env
```

最小可跑配置示例：

```env
APP_PRODUCTION_MODE=false
JWT_SECRET=replace-with-strong-secret
INTERNAL_API_TOKEN=replace-with-internal-token

MYSQL_HOST=mysql
MYSQL_PORT=3306
MYSQL_DATABASE=ai_supermarket_v1
MYSQL_USERNAME=root
MYSQL_PASSWORD=root123456

REDIS_HOST=redis
REDIS_PORT=6379
REDIS_PASSWORD=
REDIS_DATABASE=0

TASK_QUEUE_BACKEND=rabbitmq
RABBITMQ_USERNAME=guest
RABBITMQ_PASSWORD=guest
RABBITMQ_TASK_EXCHANGE=ai.task.exchange
RABBITMQ_TASK_ROUTING_KEY=tool.normal
RABBITMQ_TASK_QUEUE=ai.tool.normal
RABBITMQ_DEAD_QUEUE=ai.tool.normal.dead
RABBITMQ_RETRY_QUEUE_PREFIX=ai.tool.normal.retry
RABBITMQ_RETRY_DELAYS_MS=5000,30000,120000
RABBITMQ_MAX_RETRIES=3

MODEL_PROVIDER=mock
MODEL_NAME=mock

WECHAT_PAY_ENABLED=false
ALIPAY_ENABLED=false
NGINX_HTTP_PORT=8088
```

上线前必须替换 `JWT_SECRET`、`INTERNAL_API_TOKEN`、支付密钥和真实模型 Key；不要提交真实 `.env`。

## 3. 访问入口

直连入口：

| 服务 | 地址 |
| --- | --- |
| 用户端 | `http://127.0.0.1:5173` |
| 管理端 | `http://127.0.0.1:5174` |
| 后端健康检查 | `http://127.0.0.1:8080/api/health` |
| 用户 API ping | `http://127.0.0.1:8080/api/v1/ping` |
| 管理 API ping | `http://127.0.0.1:8080/api/admin/v1/ping` |
| RabbitMQ 管理台 | `http://127.0.0.1:15672` |
| MySQL | `127.0.0.1:3307` |
| Redis | `127.0.0.1:6379` |

可选 Nginx 入口：

```powershell
docker compose -f deploy/docker-compose.yml -f deploy/docker-compose.nginx.yml up -d
```

| 路径 | 说明 |
| --- | --- |
| `http://127.0.0.1:8088/` | 用户端 |
| `http://127.0.0.1:8088/admin/` | 管理端 |
| `http://127.0.0.1:8088/api/health` | 后端健康检查 |

Nginx 会阻断 `/api/internal/`，内部 Worker/Agent 接口只能在服务内网调用。SSE 接口已按长连接配置 `proxy_buffering off` 和较长超时。

## 4. 数据库初始化

Docker Compose 的 MySQL 服务会把 `sql/` 挂载到 `/docker-entrypoint-initdb.d`，首次创建 `mysql_data` volume 时自动执行初始化脚本：

```text
sql/001_init_v1.sql
sql/002_seed_*.sql ... sql/014_execution_platform_commerce.sql
```

后端启动时还会执行 `DataInitializer`，补齐兼容字段、商城/模型渠道表、默认套餐、模型池 Demo 节点，以及默认登录账号。

验证数据库：

```powershell
docker exec ai-supermarket-mysql mysql -uroot -proot123456 -e "SHOW TABLES FROM ai_supermarket_v1;"
docker exec ai-supermarket-mysql mysql -uroot -proot123456 ai_supermarket_v1 -e "SELECT username,user_type,status FROM users;"
docker exec ai-supermarket-mysql mysql -uroot -proot123456 ai_supermarket_v1 -e "SELECT plan_code,plan_type,price_cents,credit_amount FROM subscription_plans;"
docker exec ai-supermarket-mysql mysql -uroot -proot123456 ai_supermarket_v1 -e "SELECT pool_code,provider,model_name,status FROM model_channel_pools;"
```

如需完全重建本地数据库，先确认不需要保留本地数据，再执行：

```powershell
docker compose -f deploy/docker-compose.yml down -v
docker compose -f deploy/docker-compose.yml up -d
```

## 5. 测试账号

当前后端启动时自动创建：

| 角色 | 账号 | 密码 | 登录入口 |
| --- | --- | --- | --- |
| 管理员 | `admin` | `123456` | `http://127.0.0.1:5174` 或 `/api/admin/v1/auth/login` |
| 普通用户 | `user1` | `123456` | `http://127.0.0.1:5173` 或 `/api/v1/auth/login` |

如果需要禁用用户测试，可以在管理端用户管理页切换用户状态，或仅在本地测试库执行：

```powershell
docker exec ai-supermarket-mysql mysql -uroot -proot123456 ai_supermarket_v1 -e "UPDATE users SET status='DISABLED' WHERE username='user1';"
```

恢复：

```powershell
docker exec ai-supermarket-mysql mysql -uroot -proot123456 ai_supermarket_v1 -e "UPDATE users SET status='ACTIVE' WHERE username='user1';"
```

## 6. 冒烟测试

健康检查：

```powershell
curl http://127.0.0.1:8080/api/health
curl http://127.0.0.1:8080/api/v1/ping
curl http://127.0.0.1:8080/api/admin/v1/ping
```

用户登录并保存 token：

```powershell
$userLogin = Invoke-RestMethod -Method Post -Uri http://127.0.0.1:8080/api/v1/auth/login -ContentType "application/json" -Body '{"account":"user1","password":"123456"}'
$userToken = $userLogin.data.token
```

管理员登录并保存 token：

```powershell
$adminLogin = Invoke-RestMethod -Method Post -Uri http://127.0.0.1:8080/api/admin/v1/auth/login -ContentType "application/json" -Body '{"account":"admin","password":"123456"}'
$adminToken = $adminLogin.data.token
```

管理端接口冒烟：

```powershell
Invoke-RestMethod -Uri http://127.0.0.1:8080/api/admin/v1/tools -Headers @{ Authorization = "Bearer $adminToken" }
Invoke-RestMethod -Uri http://127.0.0.1:8080/api/admin/v1/users -Headers @{ Authorization = "Bearer $adminToken" }
Invoke-RestMethod -Uri http://127.0.0.1:8080/api/admin/v1/commerce/plans -Headers @{ Authorization = "Bearer $adminToken" }
```

用户端接口冒烟：

```powershell
Invoke-RestMethod -Uri http://127.0.0.1:8080/api/v1/tools -Headers @{ Authorization = "Bearer $userToken" }
Invoke-RestMethod -Uri http://127.0.0.1:8080/api/v1/credits/account -Headers @{ Authorization = "Bearer $userToken" }
Invoke-RestMethod -Uri http://127.0.0.1:8080/api/v1/model-workbench/plans -Headers @{ Authorization = "Bearer $userToken" }
```

## 7. Mock 支付

本地和上线演练优先使用 `MOCK` 渠道，不依赖微信或支付宝真实回调。

创建算力包订单：

```powershell
$plans = Invoke-RestMethod -Uri http://127.0.0.1:8080/api/v1/model-workbench/plans -Headers @{ Authorization = "Bearer $userToken" }
$computePlan = $plans.data | Where-Object { $_.plan_type -eq "COMPUTE_CREDIT" } | Select-Object -First 1
$orderBody = @{ productType = "COMPUTE_CREDIT"; productId = $computePlan.id; channel = "MOCK" } | ConvertTo-Json
$order = Invoke-RestMethod -Method Post -Uri http://127.0.0.1:8080/api/v1/payments/orders -Headers @{ Authorization = "Bearer $userToken" } -ContentType "application/json" -Body $orderBody
```

模拟支付成功：

```powershell
$orderId = $order.data.id
Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:8080/api/v1/payments/orders/$orderId/mock-pay" -Headers @{ Authorization = "Bearer $userToken" }
```

验证订单、履约和算力流水：

```powershell
Invoke-RestMethod -Uri http://127.0.0.1:8080/api/v1/payments/orders -Headers @{ Authorization = "Bearer $userToken" }
Invoke-RestMethod -Uri http://127.0.0.1:8080/api/v1/credits/account -Headers @{ Authorization = "Bearer $userToken" }
docker exec ai-supermarket-mysql mysql -uroot -proot123456 ai_supermarket_v1 -e "SELECT id,order_no,channel,status,paid_at FROM payment_orders ORDER BY id DESC LIMIT 5;"
docker exec ai-supermarket-mysql mysql -uroot -proot123456 ai_supermarket_v1 -e "SELECT order_id,status FROM payment_fulfillments ORDER BY id DESC LIMIT 5;"
```

真实支付开关默认关闭。接入微信或支付宝前，需要把 `.env` 中对应 `*_ENABLED` 改为 `true` 并配置完整商户参数；回调入口是 `/api/v1/payments/{channel}/callback`。

## 8. 模型渠道池

初始化后会创建模型渠道池和 Demo 节点：

| 资源 | 默认数据 |
| --- | --- |
| 套餐 | `compute_starter_100`、`global_model_monthly` |
| 模型池 | `gemini_pro_pool`、`gpt_pool`、`claude_pool` |
| Demo 节点 | `gemini-demo-01`、`gpt-demo-01` |

管理端验证：

```powershell
Invoke-RestMethod -Uri http://127.0.0.1:8080/api/admin/v1/commerce/pools -Headers @{ Authorization = "Bearer $adminToken" }
Invoke-RestMethod -Uri http://127.0.0.1:8080/api/admin/v1/commerce/nodes -Headers @{ Authorization = "Bearer $adminToken" }
Invoke-RestMethod -Uri http://127.0.0.1:8080/api/admin/v1/commerce/capacity-stats -Headers @{ Authorization = "Bearer $adminToken" }
```

用户侧购买 `global_model_monthly` 后，可访问模型工作台池列表：

```powershell
$modelPlan = $plans.data | Where-Object { $_.plan_type -eq "MODEL_CHANNEL" } | Select-Object -First 1
$modelOrderBody = @{ productType = "MODEL_CHANNEL"; productId = $modelPlan.id; channel = "MOCK" } | ConvertTo-Json
$modelOrder = Invoke-RestMethod -Method Post -Uri http://127.0.0.1:8080/api/v1/payments/orders -Headers @{ Authorization = "Bearer $userToken" } -ContentType "application/json" -Body $modelOrderBody
Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:8080/api/v1/payments/orders/$($modelOrder.data.id)/mock-pay" -Headers @{ Authorization = "Bearer $userToken" }
Invoke-RestMethod -Uri http://127.0.0.1:8080/api/v1/model-workbench/pools -Headers @{ Authorization = "Bearer $userToken" }
```

真实模型调用前，应在管理端把节点 `base_url`、`api_key`、`model_name` 替换为真实渠道，并把 Demo 备注清理或下线。

## 9. RabbitMQ 和 Redis 验证

RabbitMQ 管理台：

```text
URL: http://127.0.0.1:15672
Username: guest
Password: guest
```

RabbitMQ 命令验证：

```powershell
docker exec ai-supermarket-rabbitmq rabbitmq-diagnostics ping
docker exec ai-supermarket-rabbitmq rabbitmqctl list_queues name messages messages_ready messages_unacknowledged
docker exec ai-supermarket-rabbitmq rabbitmqctl list_exchanges name type
```

任务队列相关默认名称：

| 用途 | 名称 |
| --- | --- |
| Exchange | `ai.task.exchange` |
| Routing key | `tool.normal` |
| 主队列 | `ai.tool.normal` |
| 死信队列 | `ai.tool.normal.dead` |
| 重试队列前缀 | `ai.tool.normal.retry` |

Redis 命令验证：

```powershell
docker exec ai-supermarket-redis redis-cli ping
docker exec ai-supermarket-redis redis-cli INFO server
docker exec ai-supermarket-redis redis-cli DBSIZE
```

如果临时切回 Redis 队列后端，可在 `.env` 设置：

```env
TASK_QUEUE_BACKEND=redis
AI_TASK_QUEUE=ai:task:queue
```

然后重启后端和 worker：

```powershell
docker compose -f deploy/docker-compose.yml restart backend worker
```

## 10. 常见问题

| 现象 | 排查与处理 |
| --- | --- |
| `docker compose up` 后后端连接 MySQL 失败 | 先看 `docker compose -f deploy/docker-compose.yml ps`，确认 `mysql` health 为 healthy；再看 `docker compose -f deploy/docker-compose.yml logs -f mysql backend`。 |
| SQL 没有重新执行 | MySQL 初始化脚本只在空 volume 首次创建时执行；如需重跑，确认数据可丢弃后执行 `down -v`。 |
| 前端访问 API 跨域或 404 | 直连时用户端走 `5173`、管理端走 `5174`；Nginx 模式统一走 `8088`，确认 `.env` 中前端 API 配置未指向旧地址。 |
| 管理端依赖缺失或 Next.js 报错 | 容器会执行 `npm install && npm run dev:docker`；本地手动启动时需要在 `admin-frontend` 目录执行 `npm install`。 |
| Worker 没有消费任务 | 确认 `TASK_QUEUE_BACKEND`、RabbitMQ 队列名在 backend 和 worker 中一致；查看 `worker` 日志和 RabbitMQ 队列堆积。 |
| RabbitMQ 登录失败 | 默认账号来自 `.env` 的 `RABBITMQ_USERNAME/RABBITMQ_PASSWORD`，本地默认 `guest/guest`。 |
| Redis 验证失败 | 确认 `ai-supermarket-redis` 运行中，且后端容器内使用 `redis:6379`，宿主机使用 `127.0.0.1:6379`。 |
| Mock 支付失败 | 订单必须使用 `channel=MOCK`，否则 `/mock-pay` 会返回参数错误。 |
| 真实支付创建订单失败 | 微信/支付宝 Provider 目前要求完整配置，默认关闭；上线前必须补齐商户参数和回调地址。 |
| 模型渠道池不可见 | 用户需要购买 `MODEL_CHANNEL` 套餐；管理端可直接查看 `/api/admin/v1/commerce/pools` 和 `/nodes`。 |
| 生产模式启动失败 | `APP_PRODUCTION_MODE=true` 时必须配置强随机 `JWT_SECRET` 和 `INTERNAL_API_TOKEN`，不要使用本地默认值。 |

## 11. 回滚方案

应用级回滚：

```powershell
docker compose -f deploy/docker-compose.yml stop backend worker agent-service admin-frontend user-web
git status --short
git switch <last-known-good-branch>
docker compose -f deploy/docker-compose.yml up -d backend worker agent-service admin-frontend user-web
```

配置级回滚：

```powershell
Copy-Item .env .env.bad -Force
Copy-Item .env.example .env -Force
docker compose -f deploy/docker-compose.yml restart
```

Nginx 入口回滚为直连入口：

```powershell
docker compose -f deploy/docker-compose.yml -f deploy/docker-compose.nginx.yml stop nginx
```

队列后端从 RabbitMQ 回滚到 Redis：

```env
TASK_QUEUE_BACKEND=redis
```

```powershell
docker compose -f deploy/docker-compose.yml restart backend worker
```

数据库回滚建议：

1. 上线前先备份生产库或快照。
2. 本地测试可使用 `docker compose -f deploy/docker-compose.yml down -v` 清空 volume 后重建。
3. 生产环境不要直接执行 `down -v`；应使用数据库备份恢复或按迁移脚本编写反向 SQL。
4. 支付相关表包含 `payment_orders`、`payment_transactions`、`payment_fulfillments`，回滚前先导出订单和履约记录，避免重复发放算力或会员。

上线失败快速止血：

```powershell
docker compose -f deploy/docker-compose.yml logs --tail=200 backend worker agent-service
docker compose -f deploy/docker-compose.yml restart backend worker
docker compose -f deploy/docker-compose.yml -f deploy/docker-compose.nginx.yml stop nginx
```

如果问题来自新模型渠道节点，优先在管理端将节点状态改为 `DISABLED`，或执行本地测试 SQL：

```powershell
docker exec ai-supermarket-mysql mysql -uroot -proot123456 ai_supermarket_v1 -e "UPDATE model_channel_nodes SET status='DISABLED' WHERE node_code='<node-code>';"
```
