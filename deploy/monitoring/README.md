# 监控栈（Prometheus + Grafana）

## 目标

第一阶段先做基础可观测性，覆盖：

- 主机 CPU / 内存 / 磁盘
- Docker 容器 CPU / 内存
- Docker 容器 stdout / stderr 日志（Alloy -> Loki，保留 7 天）
- Prometheus target 存活
- Nginx / Backend / Agent Service / 公网首页 HTTP 探活
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
- Loki 就绪检查: `http://127.0.0.1:3100/ready`
- Alloy 指标: `http://127.0.0.1:12345/metrics`

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
LOKI_PORT=3100
ALLOY_PORT=12345
GRAFANA_ROOT_URL=https://wlcloudai.com/grafana/
PROMETHEUS_RETENTION=15d
CADVISOR_IMAGE=
GRAFANA_THEME=dark
GRAFANA_LANGUAGE=zh-Hans
```

## 生产访问建议

不要直接开放 3001/9091 到公网。Grafana 通过 `https://wlcloudai.com/grafana/` 访问；Prometheus 仅用于服务器本机或 SSH 隧道排查。

## 已预置面板

Grafana 会自动加载：

- `生产运维驾驶舱`（UID：`ops-command-center-cn`）：值班首页分层展示黄金四指标、登录与 AI 任务成功率、关键依赖、采集链路和异常下钻；无请求或指标缺失时显示 `无数据`，不映射为绿色。
- `AI Tool Market 基础设施总览`：Target 健康、失败 Target、HTTP/TCP 探测、容器资源和文件系统/卷挂载使用率。
- `AI工具市场-生产可靠性总览`：真实 HTTP histogram P95、Tomcat busy/max threads、线程利用率和业务指标。
- `生产容器日志`：全部 Docker 容器 stdout/stderr 日志，可按容器、Compose 服务、日志流（stream）、关键字和 TraceId 查询。
- `认证安全地图态势`：Grafana 内置 2D Geomap，地图仅显示地域聚合点；IP 明细在表格中跳转管理端人工风控。

## 生产运维驾驶舱

驾驶舱默认时间范围为最近 1 小时，刷新间隔为 15 秒。首页状态统一使用以下四态：

- `正常`：当前有有效指标，且指标处于正常阈值内。
- `风险`：当前有有效指标，但已进入需要关注的风险阈值。
- `故障`：当前有有效指标，且已达到故障阈值或关键依赖不可用。
- `无数据`：所选时段没有请求或指标缺失；该状态不映射为绿色，也不等同于数值 0 或正常。

核心 API 与长耗时 AI 任务分开观察，避免两类不同耗时特征相互稀释。API P95 来自服务端 `HTTP histogram`，不使用平均耗时，也不使用 Blackbox 探活数据替代。

驾驶舱和下钻看板只用于观察，不提供重启、扩容、封禁、解封或告警编辑操作。基础设施总览、生产可靠性总览、生产容器日志和认证安全地图态势四个详细看板的下钻链接均保留当前时间范围。IP 风控仅允许在 `/admin/monitoring` 由人工操作。

## 日志与 TraceId

Alloy 自动发现 Docker 容器并采集 stdout/stderr，不使用固定容器白名单。Loki 日志保留时间为 7 天。进入“生产容器日志”看板后，TraceId 留空会显示最近日志；输入 TraceId 或 `X-Request-Id` 可跨容器检索同一请求链路。应用必须把该标识写入日志正文，查询才会命中。

当前不引入 OpenTelemetry Collector 和 Tempo。现阶段 TraceId 日志关联已经满足单机排障，避免新增链路存储与采集资源开销；需要服务拓扑、Span 耗时和采样策略时再评估引入。

## 卷监控边界

Grafana 仅展示 Node Exporter/cAdvisor 提供的文件系统、挂载点容量和使用率指标，不浏览或索引 Docker 卷内文件。容器日志统一从 stdout/stderr 采集；确需采集文件日志时，只能在 Alloy 中增加明确的只读白名单路径。

## 只读配置

预置看板关闭 UI 更新，Prometheus、Loki 和 MySQL 数据源均不可在 Grafana UI 编辑。需要调整看板或数据源时修改仓库配置并通过 CD 发布，避免线上配置漂移。

## 配置验证

```bash
promtool check config /etc/prometheus/prometheus.yml
alloy validate /etc/alloy/config.alloy
```

## 上线后只读验收

1. 在 Grafana 按 UID `ops-command-center-cn` 打开生产运维驾驶舱，确认默认最近 1 小时、15 秒刷新和四态图例。
2. 在 Prometheus 分别查询 `up{job="loki"}`、`up{job="alloy"}`、`up{job="cadvisor"}`，确认结果均为 1。
3. 将驾驶舱中的 API P95、Tomcat 线程、容器资源和卷使用率与对应详细看板对照。
4. 逐一打开四个详细看板的下钻链接，确认保留当前时间范围。
5. 在无流量或指标缺失的场景确认显示 `无数据`，而不是绿色 0。

以上验收仅允许只读查看，不得停止容器、制造错误、修改凭据、证书、SSH/CD 配置或 IP 状态。
