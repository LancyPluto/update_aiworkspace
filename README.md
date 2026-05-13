# AI Tool Market

AI 工具市场本地联调项目，包含用户端、管理端、后端、Worker、MySQL 和 Redis。

## 一键启动

在项目根目录执行：

```powershell
docker compose -f deploy/docker-compose.yml up -d
```

首次启动会下载镜像并安装 Maven、npm、pip 依赖，耗时会比较久。后续重启可以执行：

```powershell
docker compose -f deploy/docker-compose.yml restart
```

如果本机之前跑过旧版本容器，可以使用：

```powershell
docker compose -f deploy/docker-compose.yml up -d --remove-orphans
```

## 本地访问

| 服务 | 地址 |
| --- | --- |
| 用户端 | http://127.0.0.1:5173 |
| 管理端 | http://127.0.0.1:5174 |
| 后端 API | http://127.0.0.1:8080 |
| MySQL | 127.0.0.1:3307 |
| Redis | 127.0.0.1:6379 |

## 测试账号

| 角色 | 账号 | 密码 |
| --- | --- | --- |
| 管理员 | admin | 123456 |
| 普通用户 | user1 | 123456 |
| 禁用用户 | disabled_user | 123456 |

## Docker 服务

`deploy/docker-compose.yml` 会启动以下服务：

- `mysql`: MySQL 8，自动执行 `sql` 目录初始化脚本
- `redis`: Redis 7
- `backend`: Spring Boot 后端，端口 `8080`
- `admin-frontend`: Next.js 管理端，端口 `5174`
- `user-web`: Vite 用户端，端口 `5173`
- `worker`: Python Worker，消费 Redis 队列并回写任务结果

默认 Worker 使用：

```env
MODEL_PROVIDER=mock
```

因此不配置真实大模型 Key 也可以测试任务创建、队列消费、结果回写和算力流水。接入真实模型时再配置：

```env
MODEL_PROVIDER=deepseek
MODEL_API_BASE_URL=https://api.deepseek.com
MODEL_API_KEY=your-real-key
MODEL_NAME=deepseek-chat
```

## 常用检查

查看容器状态：

```powershell
docker compose -f deploy/docker-compose.yml ps
```

查看后端日志：

```powershell
docker compose -f deploy/docker-compose.yml logs -f backend
```

查看 Worker 日志：

```powershell
docker compose -f deploy/docker-compose.yml logs -f worker
```

验证后端接口：

```powershell
curl http://127.0.0.1:8080/api/v1/ping
```

## 注意事项

- 本机端口 `8080`、`5173`、`5174`、`3307`、`6379` 不能被其他服务占用。
- 如果数据库结构和种子数据异常，通常是旧 Docker volume 残留导致，可以在确认不需要旧数据后清理 volume 再启动。
- 两个前端都连接同一个后端服务，用户端通过 Vite 代理访问后端，管理端在本地开发端口会直连 `http://127.0.0.1:8080`。
- 当前分支用于团队后端核心集成和本地联调，推荐组员拉取 `integration/backend-core-team-merge-20260510` 后按本文档启动。
