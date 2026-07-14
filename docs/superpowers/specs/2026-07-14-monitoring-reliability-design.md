# 生产监控可靠性与 IP 风控设计

## 目标

在不引入 OpenTelemetry 和 Tempo 的前提下，恢复并完善现有 Prometheus、Grafana、Loki、Alloy 监控链路：

- Grafana 能查看所有生产 Docker 容器的标准输出日志。
- TraceId 能关联 Backend、Agent Service、Worker 等服务日志。
- cAdvisor 提供容器 CPU 和内存指标。
- Blackbox HTTP 探测只检测真实生产入口，不探测不存在的 Vite 端口。
- Grafana 提供以 2D Geomap 为核心的登录审计地图态势看板。
- `/admin/monitoring` 提供人工 IP 风控、快捷日志调查和存储健康入口。

生产凭据、证书、SSH/CD 安全改造不在本次范围内。

## 已确认决策

- IP 风控第一版仅支持人工处置，不自动封禁。
- 日志保留 7 天。
- 采集所有 Docker 容器标准输出日志。
- 卷只展示容量、挂载和健康状态；仅采集白名单文本日志，不浏览任意卷内容。
- Grafana 负责地图态势和深度日志查询。
- `/admin/monitoring` 负责有权限校验和操作审计的 IP 封禁、修改和解封。
- 不引入 OTel、Tempo 或新的可观测性存储系统。

## 根因与修复边界

### Loki 与 Alloy

当前 Compose 定义 `alloy`，但部署服务检测和分类仍使用 `promtail`。`docker compose up` 因不存在 `promtail` 而使整组监控服务启动失败，部署脚本只记录 warning 后继续，最终保留 Grafana 和 Prometheus，却没有 Loki 与 Alloy。

修复：

- 服务检测输出 `alloy`，不再输出 `promtail`。
- 部署脚本把 `alloy` 归类为核心监控服务。
- Loki、Alloy、Prometheus、Grafana、node-exporter、blackbox-exporter 和 cAdvisor 的核心启动失败应使监控部署失败。
- 发布健康检查逐个验证核心监控容器，而不是只匹配任意一个容器名。
- 验证 Loki `/ready`、Prometheus `/-/ready`、Grafana `/api/health` 和 Alloy `/metrics`。

### 容器日志

Alloy 使用 Docker discovery 和宿主机只读 JSON 日志目录。移除固定八个容器的 keep 正则，使所有 Docker 容器都进入 Loki。

Loki 标签限制为：

- `container`
- `service`
- `compose_project`
- `stream`

不把 `container_id`、TraceId、IP、账号或任务 ID 作为 Loki 标签。TraceId 和 IP 保留在日志正文中，通过 LogQL 行过滤查询。

### cAdvisor

生产没有 cAdvisor 容器和镜像，导致 `container_cpu_usage_seconds_total` 与 `container_memory_working_set_bytes` 完全不存在。

修复：

- cAdvisor 默认使用 `m.daocloud.io/gcr.io/cadvisor/cadvisor:v0.49.1`，并保留 `CADVISOR_IMAGE` 环境变量用于生产覆盖。
- cAdvisor 不再作为可静默失败的可选服务。
- 发布验证要求 `up{job="cadvisor"} == 1`。

### Blackbox HTTP

当前四个 HTTP 目标中，Backend 和 Agent Service 成功，Nginx 与 User Web 失败。

- Nginx 失败：Blackbox 使用 `Host: nginx`，命中默认虚拟主机并被 `return 444` 关闭连接。
- User Web 失败：生产容器只构建静态文件到 `/dist-out`，不监听 `5173`。

修复：

- 为 Nginx 内部探测使用带 `Host: wlcloudai.com` 的专用 Blackbox HTTP 模块。
- 移除 `http://user-web:5173/` 生产探测。
- 用 Nginx 的用户站点静态文件入口验证用户前端可用性。
- 分别展示每个 HTTP 探测目标，汇总成功率仅作为补充。

### Grafana 总览

现有 `sum(up)` 只显示成功目标数量，并在至少 6 个成功时显示绿色，会掩盖失败 target。

修复：

- 显示成功目标数、总目标数和成功率。
- 增加失败 target 表格。
- 容器 CPU、内存面板在 cAdvisor 缺失时显示明确说明。
- 日志面板增加容器、服务、stream、关键字和 TraceId 过滤。
- TraceId 为空时展示最近日志，不产生误导性的空 Trace 查询状态。

## Grafana 地图态势

登录审计看板继续使用内置 2D Geomap，不引入 3D 插件。

页面结构：

1. 全宽世界地图作为首要面板，展示经纬度聚合点、失败热度和事件数量。
2. 时间范围、事件类型、结果、国家和地区筛选。
3. TOP 风险 IP、TOP 国家/城市、登录失败率和认证趋势。
4. 点击地图点或 IP 表格，通过 Data Link 跳转 `/admin/monitoring?ip=<encoded-ip>`。
5. Grafana 保持只读，不执行封禁操作。

地图数据只包含聚合经纬度、地区、事件类型、结果和数量，不向地图面板发送账号、User-Agent 或完整事件明细。

## `/admin/monitoring` IP 风控

管理后台提供四个视图：

- IP 风控
- 认证事件
- 日志与 Trace
- 存储健康

IP 风控操作：

- 按 IP、CIDR、状态、范围、风险等级和时间筛选。
- 查看失败次数、尝试账号数、首次/最近活动、地区和关联 TraceId。
- 人工创建临时封禁，默认 `AUTH_ONLY`。
- 修改到期时间、备注和启用状态。
- 解封采用软失效，不物理删除记录。
- 创建、修改和解封必须填写原因并写管理员操作审计。

日志与 Trace 视图通过后端只读代理查询 Loki，不把 Loki 直接暴露给浏览器。查询必须限制时间范围、结果条数和允许的 LogQL 结构。

存储健康只展示 Docker 卷名称、挂载目标、容量、使用率和最近采集状态。数据库、Redis、RabbitMQ 等卷内容不提供文件浏览。

## 数据流

```text
Docker stdout/stderr -> Alloy -> Loki -> Grafana logs panels
                                  -> Backend read-only log query -> /admin/monitoring

Backend auth event -> MySQL auth_security_events -> Grafana Geomap
                                               -> Admin IP risk query

Admin block action -> Admin API -> security_ip_blocks
                              -> admin_operation_logs
```

## 错误处理

- Alloy 无法读取 Docker 日志目录时记录采集错误指标并触发 target down。
- Loki 不可用时 Grafana 与管理后台显示“日志服务不可用”，不显示空数据。
- GeoIP 缺失时保留认证事件，但地图标记为“无定位数据”。
- cAdvisor 不可用时基础设施总览标记采集器失败，不把空面板解释成零使用率。
- IP 封禁写入失败时不得显示成功；管理员操作审计与封禁变更保持一致。

## 测试与验收

### 静态契约测试

- 服务检测包含 `alloy` 且不包含 `promtail`。
- 部署脚本将 `alloy` 和 cAdvisor 归为核心监控服务。
- Alloy 配置不保留固定容器白名单，不使用 `container_id` 标签。
- Blackbox 不探测 `user-web:5173`，Nginx 探测携带正确 Host。
- Loki retention 为 168 小时。

### 本地 Compose 验证

- `docker compose config --services` 包含全部监控服务。
- 所有配置文件能被对应组件解析。
- Grafana dashboard JSON 可解析，datasource UID 存在。

### 生产验收

- Loki、Alloy、cAdvisor 均为 running。
- Prometheus 中 `up{job="alloy"} == 1`、`up{job="cadvisor"} == 1`。
- 容器 CPU 和内存查询返回至少一个生产容器序列。
- 所有预期 HTTP probe 返回成功。
- Loki labels 中能看到生产容器，并能按 TraceId 查到跨服务日志。
- Grafana Geomap 在存在公网认证事件时显示点位。
- 从 Grafana IP Data Link 能打开 `/admin/monitoring` 对应 IP。

## 分期

### 第一阶段：恢复监控链路

- 修复 CD 服务名与失败处理。
- 启动 Loki、Alloy、cAdvisor。
- 修复 Blackbox 目标和总览面板。
- 验证全容器日志与 TraceId 查询。

### 第二阶段：地图态势与人工 IP 风控

- 完善 Grafana 2D 地图态势。
- 实现 IP 封禁数据模型、管理 API 和操作审计。
- 实现 `/admin/monitoring` 风控、日志和存储健康视图。
