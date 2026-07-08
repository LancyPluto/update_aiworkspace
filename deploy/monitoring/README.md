# 监控栈（Prometheus + Grafana）

## 目标

第一阶段先做基础可观测性，覆盖：

- 主机 CPU / 内存 / 磁盘
- Docker 容器 CPU / 内存
- Prometheus target 存活
- Nginx / Backend / Agent Service / User Web HTTP 探活
- MySQL / Redis / RabbitMQ / 代理端口 TCP 探活

> 当前 Prometheus 默认只绑定 `127.0.0.1`，避免公网裸奔；Grafana 宿主机端口也只绑定本机，公网入口统一走 Nginx HTTPS `/grafana/`。

## 启动

在 `deploy/` 目录执行：

```bash
docker compose \
  -f docker-compose.yml \
  -f docker-compose.nginx.yml \
  -f docker-compose.monitoring.yml \
  up -d
```

访问：

- 生产 Grafana: `https://wlcloudai.com/grafana/`
- Grafana 健康检查: `http://127.0.0.1:3001/api/health`
- Prometheus: `http://127.0.0.1:9091`

默认账号：

- 用户名：`admin`
- 密码：`123456`

> `123456` 仅用于初期验收。生产稳定后建议在 `deploy/.env` 中改成强密码，并执行 Grafana 密码重置。

生产可在 `deploy/.env` 或系统环境中覆盖：

```env
GRAFANA_ADMIN_USER=admin
GRAFANA_ADMIN_PASSWORD=请改成强密码
PROMETHEUS_PORT=9091
GRAFANA_PORT=3001
GRAFANA_ROOT_URL=https://wlcloudai.com/grafana/
PROMETHEUS_RETENTION=15d
CADVISOR_IMAGE=
```

## 生产访问建议

不要直接开放 3001/9091 到公网。Grafana 通过 `https://wlcloudai.com/grafana/` 访问；Prometheus 仅用于服务器本机或 SSH 隧道排查。

## 已预置面板

Grafana 会自动加载：

- `AI Tool Market Overview`

## 后续增强

第二阶段建议补：

- Spring Boot Actuator `/actuator/prometheus`
- Agent Service FastAPI `/metrics`
- Worker 任务成功率 / 失败率 / 重试次数
- 支付回调成功率 / 失败率
- 模型供应商调用耗时 / 错误码分类
- Alertmanager 或 Grafana Alerting
