# 生产运维驾驶舱设计

## 背景

现有监控体系已经拆分为基础设施总览、生产可靠性、生产容器日志和认证安全地图。它们适合深入排查，但值班人员仍需要在多个看板之间切换，才能回答三个最先发生的问题：

1. 现在是否有用户可感知的故障？
2. 影响的是入口、应用、异步任务、依赖还是采集链路？
3. 应该进入哪个详细看板继续排查？

本设计借鉴成熟运维驾驶舱的高信息密度、黄金指标、状态分层和快捷下钻，但不复制参考系统的专有指标，也不引入当前系统没有的数据。

## 已确认决策

- 新增独立的 `生产运维驾驶舱`，保留现有详细看板。
- 首要用户是生产值班和排障人员。
- 使用分层驾驶舱布局，不使用单屏堆叠或服务矩阵作为默认入口。
- 不使用 0-100 综合健康分；状态只能是 `正常`、`风险`、`故障` 或 `无数据`。
- 首屏同时展示黄金四指标和登录、AI 任务两个核心业务指标。
- 核心同步 API 和长耗时 AI 任务使用不同指标口径。
- Grafana 看板继续只读。
- 本阶段只新增驾驶舱，不新增通知告警规则。
- 不引入自定义前端、第三方 Grafana 插件、OpenTelemetry 或 Tempo。
- CD 后只做生产只读验收，不主动制造故障。

## 方案比较

### 方案 A：单屏总控

将健康状态、黄金指标、业务指标和全部基础设施状态压入一个首屏。它最接近参考页面，但在 Grafana 中容易形成密集小面板，窄屏可读性较差，后续增加服务时扩展成本高。

### 方案 B：分层驾驶舱（采用）

首屏只负责发现问题：总体状态、黄金指标、核心业务、关键基础设施和异常入口。日志、地图、容器资源和服务细节通过 Dashboard Link 或 Data Link 下钻。该方案保留参考页面的值班效率，同时符合 Grafana 的原生使用方式。

### 方案 C：服务矩阵

每个服务一行，横向比较 UP、QPS、错误、P95、CPU、内存和日志。服务数量扩大后更易扫描，但当前值班人员不容易第一眼判断用户影响和全局严重度。

## 看板身份与导航

- 文件：`deploy/monitoring/grafana/dashboards/ops-command-center-cn.json`
- UID：`ops-command-center-cn`
- 标题：`生产运维驾驶舱`
- 默认时间范围：最近 1 小时
- 默认刷新：15 秒
- 默认主题：沿用 Grafana 深色主题
- 看板属性：provisioned、只读、不可在 UI 保存覆盖

看板顶部使用 Grafana 原生 Dashboard Links，并开启 `keepTime` 和 `includeVars`：

- `基础设施总览`
- `生产可靠性总览`
- `生产容器日志`
- `认证安全地图态势`

`/admin/monitoring` 不作为普通 Dashboard Link。只有包含具体 IP 的表格 Data Link 才跳转到该页面执行人工风控。

## 变量

### `component`

- 类型：Grafana custom variable
- 默认：`All`
- 候选：`All`、`Backend`、`Agent Service`、`Worker`
- 值使用稳定的服务名片段：`.*`、`backend`、`agent-service`、`worker`
- 作用范围：总体状态来源、失败 Target、容器 CPU/内存和错误日志下钻
- 不作用于全局黄金指标、登录成功率和 AI 任务成功率；这些面板标题必须明确标注为全局指标

Prometheus job 使用 `job=~".*${component:regex}.*"`，cAdvisor/Loki 使用容器或 service 标签匹配相同片段。这样一个变量可以跨数据源收敛排查范围，但不会改变全局用户影响口径。

### `uri`

- 类型：Prometheus query variable
- 默认：`All`
- 来源：`http_server_requests_seconds_count{job="backend-actuator",uri!~"/actuator.*"}`
- 用途：同步 API 的 QPS、错误率和 P95 下钻

### 时间范围

使用 Grafana 原生 time picker，不创建重复的自定义时间变量。

## 首屏布局

布局使用 Grafana 24 列网格。

### 第一层：状态与用户影响（`y=0`，`h=8`）

| 面板 | 位置 | 作用 |
|---|---|---|
| 总体状态 | `x=0,w=8,h=8` | 显示正常、风险、故障或无数据，并列出状态来源 |
| 当前流量 | `x=8,w=4,h=4` | 核心 API QPS 和异步任务吞吐 |
| 核心 API 成功率 | `x=12,w=4,h=4` | 排除 Actuator 和 AI 长任务的同步 API 成功率 |
| 核心 API 5xx 错误率 | `x=16,w=4,h=4` | 服务端错误占比 |
| 核心 API P95 | `x=20,w=4,h=4` | 来自 histogram bucket 的真实 P95 |
| 登录成功率 | `x=8,y=4,w=4,h=4` | 最近一小时登录成功率 |
| AI 任务成功率 | `x=12,y=4,w=4,h=4` | 最近一小时异步任务结果 |
| 任务排队与租约 | `x=16,y=4,w=4,h=4` | 队列积压、claim 和 lease 异常 |
| Tomcat 线程利用率 | `x=20,y=4,w=4,h=4` | busy/max threads |

### 第二层：趋势与异常（`y=8`，`h=8`）

- `黄金指标时间线`：`x=0,w=16,h=8`，同时显示 QPS、5xx 错误率和 P95。通过字段 override 保持单位独立，不把不同单位相加。
- `当前失败 Target`：`x=16,w=8,h=4`，展示 `up == 0` 的 job、instance 和 service。
- `最近错误日志`：`x=16,y=12,w=8,h=4`，展示最近 5 分钟错误日志总量与详细日志看板链接，不在驾驶舱铺完整日志流。

### 第三层：饱和度与依赖（`y=16`，`h=4`）

八个等宽状态面板，每个 `w=3`：

- 容器 CPU
- 容器内存
- MySQL
- Redis
- RabbitMQ
- cAdvisor
- Alloy/Loki
- 文件系统与卷

这一层只展示当前值、容量或采集状态。历史曲线保留在基础设施和可靠性详细看板。

## 指标口径

### 黄金指标

#### 流量

- 核心 API：`http_server_requests_seconds_count` 的速率，排除 `/actuator.*`。
- AI 任务：使用任务完成或领取指标展示独立吞吐，不混入 HTTP QPS。

#### 核心 API 成功率

以非 5xx 请求占全部核心同步 API 请求的比例计算。4xx 不作为平台不可用错误，业务限流和用户输入错误不会降低平台 SLO。

#### 核心 API 5xx 错误率

以 `status=~"5.."` 的请求速率除以核心同步 API 总请求速率。无请求时显示 `无数据`，不显示 0% 绿色。

#### 核心 API P95

必须使用：

```promql
histogram_quantile(
  0.95,
  sum by (le, method, uri) (
    rate(http_server_requests_seconds_bucket{
      job="backend-actuator",
      uri!~"/actuator.*"
    }[$__rate_interval])
  )
)
```

不允许用平均值、`_sum/_count` 或客户端探测耗时替代服务端 P95。

### 核心业务

- 登录成功率沿用 `auth_login_attempts_total` 的成功与总量比例。
- AI 任务成功率沿用 `ai_task_total` 的 `SUCCESS` 与总量比例。
- 任务排队和租约沿用 Worker/RabbitMQ 的 ready、unacked、claim 和 lease 指标。
- AI 生成、Agent 运行和 Worker 执行耗时不进入核心 API P95。

### 饱和度

- Tomcat：`tomcat_threads_busy_threads / tomcat_threads_config_max_threads * 100`
- 容器 CPU：cAdvisor 的具名 `ai-supermarket-*` 容器时序
- 容器内存：`container_memory_working_set_bytes`
- 卷：Node Exporter 文件系统使用率，仅显示容量和挂载，不浏览卷内文件
- 日志：Alloy target 状态与 Loki 最近容器日志可用性

Prometheus 配置新增 Loki 自监控抓取：`job_name: loki`、目标 `loki:3100`。驾驶舱使用 `up{job="loki"}` 判断 Loki 可抓取状态；最近容器日志是否存在仍由 Loki 查询判断。Grafana 本身不可用时驾驶舱无法展示，生产入口探测和发布健康门禁继续负责 Grafana 可用性。

## 状态与阈值

阈值只控制看板颜色和总体状态，本阶段不发送告警通知。

| 指标 | 正常 | 风险 | 故障 |
|---|---:|---:|---:|
| 核心 API 成功率 | `>=99.9%` | `99.5%-99.9%` | `<99.5%` |
| 核心 API 5xx 错误率 | `<1%` | `1%-5%` | `>=5%` |
| 核心 API P95 | `<2s` | `2s-5s` | `>=5s` |
| Tomcat 线程利用率 | `<70%` | `70%-85%` | `>=85%` |
| CPU / 内存 | `<80%` | `80%-90%` | `>=90%` |
| 文件系统 / 卷使用率 | `<75%` | `75%-90%` | `>=90%` |
| AI 任务成功率 | `>=98%` | `95%-98%` | `<95%` |

总体状态采用最严重项：

1. 核心服务、生产公网入口、cAdvisor、Alloy 或 Loki 不可用时为 `故障`。
2. 任何指标进入风险区间时为 `风险`。
3. 核心链路可用且所有有数据指标正常时为 `正常`。
4. 关键查询无序列、采集缺失或当前时间范围无请求时为 `无数据`，不能映射为绿色。

Prometheus datasource 整体不可访问时，Grafana 无法执行总体状态 PromQL。该情况必须保留为显式查询错误，不得通过 fallback 映射为 `正常`、`风险` 或 `无数据`。

总体状态面板必须同时显示触发它的具体条件，不能只显示颜色。

## 下钻规则

Data Link 必须保留 `${__url_time_range}`，并在目标看板存在同名变量时传递变量。

- 失败 Target、容器资源、卷容量 -> 基础设施总览
- P95、Tomcat、任务和队列 -> 生产可靠性总览
- 错误日志、TraceId -> 生产容器日志
- 风险 IP、认证失败 -> 认证安全地图态势
- 高风险来源 IP -> `/admin/monitoring?ip=${__data.fields.ip_address:percentencode}`

驾驶舱不执行重启、扩容、封禁、解封或告警规则修改。

## 数据流

```text
Backend Actuator ----------> Prometheus ----+
Agent / Worker metrics ----> Prometheus ----+--> 生产运维驾驶舱
cAdvisor / Node Exporter ---> Prometheus ----+
Blackbox -------------------> Prometheus ----+
Loki self metrics ----------> Prometheus ----+

Docker stdout/stderr -> Alloy -> Loki ------+--> 错误数量与日志采集状态

驾驶舱 --Data Link--> 基础设施 / 可靠性 / 日志 / Geomap
Geomap Top IP -------> /admin/monitoring 人工风控
```

驾驶舱不直接查询认证审计 MySQL，避免首页引入额外数据库压力。认证地域数据只在 Geomap 中查询。

## 错误与无数据处理

- Prometheus、Loki 查询错误：面板显示查询错误，不使用默认值掩盖。
- 时间范围内没有请求：流量、错误率、成功率和 P95 显示无数据或空闲，不显示绿色 0。
- cAdvisor 不可用：CPU/内存面板显示采集失败，不解释为零使用率。
- Alloy/Loki 没有最近容器日志：日志采集状态显示故障。
- 单个业务指标缺失：总体状态至少为无数据，详细面板保留缺失来源。
- 下钻目标不存在或变量不兼容属于验收失败。

## 测试

### 静态契约

- Dashboard JSON 可解析。
- UID 为 `ops-command-center-cn`。
- panel id 唯一，所有 panel 明确 datasource UID。
- 只使用已存在的 Prometheus/Loki 指标名。
- P95 查询包含 histogram bucket。
- 同步 API 查询排除 `/actuator.*`。
- 状态阈值与本规格一致。
- Dashboard Link 和 Data Link 保留时间范围。
- 无写操作、告警编辑或管理 API 链接。

### 本地验证

- 所有监控契约测试通过。
- 所有 Dashboard JSON 通过 UTF-8 结构化解析。
- Compose config 通过。
- Grafana provisioned dashboard provider 保持只读。

### CD 后生产只读验收

- 驾驶舱可以由 UID 打开。
- 每个 Prometheus/Loki 查询返回新鲜生产数据，或按规格显示无数据。
- P95、Tomcat、cAdvisor、Alloy/Loki 和 Blackbox 面板与对应原始查询一致。
- 所有详细看板链接可以打开并保留时间范围。
- 桌面宽屏首屏包含状态、黄金指标、核心业务和依赖状态。
- 较窄窗口没有文字、单位或面板标题重叠。

生产验收不主动停止容器、不制造错误流量、不修改告警和 IP 风控状态。

## 非目标

- 不替换现有四个详细看板。
- 不新增通知告警、静默、值班路由或告警规则管理。
- 不在 Grafana 执行 IP 封禁、解封或配置修改。
- 不增加日志或指标存储系统。
- 不浏览容器卷文件内容。
- 不修改凭据、证书、SSH 或 CD 安全设置。
