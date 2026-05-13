# 17. Docker Compose 本机一键启动方案

演示环境在本机时，建议使用 Docker Compose。它最大的价值不是“显得高级”，而是让 5 个成员的 MySQL、Redis、端口、账号密码保持一致。

## 1. 推荐策略

一周 V1 建议分两阶段：


| 阶段  | Compose 管什么                | 原因                 |
| --- | -------------------------- | ------------------ |
| 开发期 | MySQL + Redis              | 最省心，前后端热更新仍然本地跑    |
| 演示期 | MySQL + Redis，后端/Worker 可选 | 时间足够再容器化后端和 Worker |


不建议第一天就强行把所有服务容器化，否则 Dockerfile、网络、热更新会额外吃掉时间。

## 2. 推荐文件结构

放在正式项目根目录：

```text
ai-supermarket/
├── docker-compose.yml
├── .env.example
├── backend/
├── user-web/
├── admin-web/
├── worker/
└── sql/
```

## 3. 最小 docker-compose.yml

第 1 天先用这个版本，只启动 MySQL 和 Redis：

```yaml
services:
  mysql:
    image: mysql:8.0
    container_name: ai-supermarket-mysql
    restart: unless-stopped
    environment:
      MYSQL_ROOT_PASSWORD: root123456
      MYSQL_DATABASE: ai_supermarket_v1
      TZ: Asia/Shanghai
    ports:
      - "3306:3306"
    volumes:
      - mysql_data:/var/lib/mysql
      - ./sql:/docker-entrypoint-initdb.d
    command:
      - --character-set-server=utf8mb4
      - --collation-server=utf8mb4_unicode_ci

  redis:
    image: redis:7
    container_name: ai-supermarket-redis
    restart: unless-stopped
    ports:
      - "6379:6379"
    volumes:
      - redis_data:/data
    command: redis-server --appendonly yes

volumes:
  mysql_data:
  redis_data:
```

## 4. .env.example

```env
MYSQL_HOST=127.0.0.1
MYSQL_PORT=3306
MYSQL_DATABASE=ai_supermarket_v1
MYSQL_USERNAME=root
MYSQL_PASSWORD=root123456

REDIS_HOST=127.0.0.1
REDIS_PORT=6379
REDIS_PASSWORD=
REDIS_DATABASE=0

SERVER_PORT=8080
JWT_SECRET=local-dev-secret
INTERNAL_API_TOKEN=local-internal-token

MODEL_PROVIDER=deepseek
MODEL_API_BASE_URL=https://api.deepseek.com
MODEL_API_KEY=replace-with-real-key
MODEL_NAME=deepseek-chat

VITE_API_BASE_URL=http://localhost:8080
VITE_ADMIN_API_BASE_URL=http://localhost:8080
```

## 5. 本机启动命令

```bash
docker compose up -d
```

查看状态：

```bash
docker compose ps
```

停止：

```bash
docker compose down
```

清空数据库和 Redis 数据，谨慎使用：

```bash
docker compose down -v
```

## 6. 数据库初始化

建议把 `07-V1数据库初始化草案.sql` 复制到正式项目：

```text
sql/001_init_v1.sql
```

第一次 `docker compose up -d` 时 MySQL 会自动执行 `sql/` 下的初始化脚本。

如果容器已经启动过，后续修改 SQL 不会自动重跑。需要：

```bash
docker compose down -v
docker compose up -d
```

或手动进数据库执行迁移脚本。

## 7. 本地开发启动方式

推荐开发期这样跑：

```text
docker compose up -d     # MySQL + Redis
backend 本地启动          # Spring Boot 热重载/IDE 调试
worker 本地启动           # Python 日志直观看
user-web npm run dev      # Vite
admin-web npm run dev     # Vite
```

推荐端口：


| 服务    | 端口   |
| ----- | ---- |
| 后端    | 8080 |
| 用户端   | 5173 |
| 管理后台  | 5174 |
| MySQL | 3306 |
| Redis | 6379 |


## 8. 演示当天检查

```text
docker compose ps 显示 mysql/redis 正常
后端 /api/health 返回 SUCCESS
用户端能登录
管理后台能登录
Worker 日志显示 connected to redis
创建任务后 Redis 队列能被消费
```

## 9. 是否需要把后端和 Worker 也放进 Compose

如果第 6 天主链路已经稳定，可以加；如果还在修业务 Bug，不建议加。

后端/Worker 容器化的好处：

```text
演示启动更统一
减少本机 Java/Python 环境差异
```

坏处：

```text
需要 Dockerfile
需要处理镜像构建
日志排查稍慢
热更新不如本地直接启动方便
```

本项目一周版本建议：

```text
必须：MySQL + Redis Compose
可选：后端 + Worker Compose
不建议：前端开发期容器化
```

