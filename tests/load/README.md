# 生产环境只读压测

本目录使用 k6 对生产入口 `https://wlcloudai.com` 做受控的只读压测。脚本只覆盖公开查询和普通用户查询，不包含支付、充值、上传、AI 生成、工作流执行、任务创建、管理端写操作或账号注销。

## 覆盖接口

公开接口：

- `GET /api/v1/tool-categories`
- `GET /api/v1/tools`
- `GET /api/v1/tools/search`
- `GET /api/v1/model-options`
- `GET /api/v1/community/posts`

登录后接口：

- `GET /api/v1/users/me`
- `GET /api/v1/tasks`
- `GET /api/v1/credits/account`
- `GET /api/v1/credits/logs`
- `GET /api/v1/credits/usage-logs`

`K6_ACCESS_TOKEN`、`PHONE` 和 `SMS_CODE` 均未提供时，脚本只运行公开接口。提供 `K6_ACCESS_TOKEN` 时，脚本直接使用当前进程内存中的 Bearer Token；或者同时提供 `PHONE` 和 `SMS_CODE`，由 k6 在 `setup()` 中只登录一次。两种认证方式不能混用。脚本不会发送短信，验证码应先通过正式用户界面获取；手机号、验证码和 Token 都不会写入结果文件。

## 执行前检查

1. 在低流量窗口执行，并安排人员同时观察 Grafana 驾驶舱。
2. 先运行冒烟测试，确认目标、验证码、监控与停止机制正常。
3. 确认 Prometheus 的 `backend-actuator`、cAdvisor 和 Alloy target 均为 UP。
4. CPU 超过 85% 或 Tomcat busy/max threads 超过 80% 时人工按 `Ctrl+C` 停止；k6 会在 HTTP 失败率达到 1% 或全局 P95 达到 2 秒时自动停止。
5. 不要临时提高 VU、缩短 `sleep()` 或加入写接口。任何扩容都应重新评估生产风险。

## 运行

在仓库根目录执行。先安装 k6，并在 PowerShell 当前进程中设置一次性环境变量。已经取得 Bearer Token 时优先使用：

```powershell
$env:K6_ACCESS_TOKEN = "<本次临时 Bearer Token>"
$env:SMOKE = "1"
k6 run tests/load/k6-production-readonly.js
```

也可以让 k6 使用尚未消费的短信验证码登录一次：

```powershell
$env:PHONE = "<专用普通用户手机号>"
$env:SMS_CODE = "<本次短信验证码>"
$env:SMOKE = "1"
k6 run tests/load/k6-production-readonly.js
```

冒烟测试通过且监控正常后，执行约 12 分钟的正式阶梯压测：

```powershell
Remove-Item Env:SMOKE -ErrorAction SilentlyContinue
k6 run tests/load/k6-production-readonly.js
```

正式阶段为 `5 VU/2m -> 15 VU/3m -> 30 VU/3m -> 50 VU/2m -> 0 VU/2m`。执行结束后立即清除本地认证变量：

```powershell
Remove-Item Env:PHONE -ErrorAction SilentlyContinue
Remove-Item Env:SMS_CODE -ErrorAction SilentlyContinue
Remove-Item Env:K6_ACCESS_TOKEN -ErrorAction SilentlyContinue
```

结果写入 `tests/load/results/summary-<timestamp>.json`，该目录内容默认不纳入 Git。账号注销需在压测完成并确认数据可删除后，通过正式短信注销流程单独执行；压测脚本不会自动删除账号。

## Grafana / Prometheus 查看各接口 QPS

现有 Grafana 的 `AI 工具市场可靠性` 看板包含“后端接口 QPS”和 P95 面板，`生产运维驾驶舱` 也可通过 URI 变量筛选接口。压测期间可在 Prometheus 使用以下查询：

```promql
sum by (method, uri) (
  rate(http_server_requests_seconds_count{job="backend-actuator",uri!~"/actuator.*"}[1m])
)
```

接口 P95：

```promql
histogram_quantile(
  0.95,
  sum by (le, method, uri) (
    rate(http_server_requests_seconds_bucket{job="backend-actuator",uri!~"/actuator.*"}[1m])
  )
)
```

接口 5xx QPS：

```promql
sum by (method, uri) (
  rate(http_server_requests_seconds_count{job="backend-actuator",uri!~"/actuator.*",status=~"5.."}[1m])
)
```

k6 的 `name` 和 `endpoint` 标签使用固定路由名称，便于在 k6 结果中按接口聚合；Prometheus 侧使用 Spring Boot 的 `method`、`uri`、`status` 标签聚合真实服务指标。

## 匿名只读容量测试

`k6-capacity-readonly.js` 用固定到达率测试四个低风险匿名接口，不读取或传递 `K6_ACCESS_TOKEN`，也不包含写请求：

- `GET /api/v1/ping`
- `GET /api/v1/tool-categories`
- `GET /api/v1/model-options`
- `GET /api/v1/tools?pageNo=1&pageSize=20`

容量计划为 `10s` 升至 100 QPS 并保持 `3m`，之后分别用 `15s` 升至 `300 / 500 / 1000 / 1500 / 2000 QPS`，每档保持 `3m`。计划总时长为 `19m25s`，结束时最多另有 `30s` 优雅退出时间。脚本采用 `600` 个预分配 VU、最多 `2500` 个 VU，且迭代之间没有 `sleep()`。

全局 HTTP 失败率或业务失败率达到 `1%`、全局 P95 达到 `500ms` 时，k6 会主动中止；请求仅将 HTTP 200 视为预期响应，因此 3xx、429 和其他非 2xx 都会计入 HTTP 失败。每个接口使用固定的 `endpoint` 标签，四个接口按迭代序号均分流量。

每个平台使用固定的 `capacity_stage` 标签。完整 JSON summary 逐档记录状态、实际运行 hold 秒数、已调度期望数、完成请求数、完成 QPS、达成率、shortfall、P50/P95/P99/max、HTTP/业务失败率和最大 VU，并在每档下列出四个接口各自的实际 QPS。

提前终止时，正在运行的档位标记为 `interrupted`，仅按 `data.state.testRunDurationMs` 对应的实际 hold 时间计算期望量；尚未进入的档位标记为 `not_run`，不会再把完整平台请求量误称为 dropped。`shortfall` 只表示实际运行 hold 内“已调度期望数 - 完成请求数”，与 k6 的精确全局 `dropped_iterations` 分开报告。只有完整跑满、完成/期望不低于 `99%` 且 HTTP 和业务失败率都低于 `1%` 的档位才是有效容量数据。

容量测试必须使用带生产保护的 runner：

```powershell
python tests/load/run_production_load_test.py --dry-run --capacity
python tests/load/run_production_load_test.py --capacity
```

若 100 QPS 附近出现客户端超时、但 Prometheus 显示服务端低延迟且资源空闲，先使用精细 edge profile 定位公网或发压端拐点：

```powershell
python tests/load/run_production_load_test.py --dry-run --capacity-edge
python tests/load/run_production_load_test.py --capacity-edge
```

edge profile 为 `50 / 60 / 70 / 80 / 90 / 100 QPS`，每档保持 `2m`，每次用 `10s` 升到下一档，总计划时长 `13m`。它与完整 profile 使用相同的四个只读接口、k6 阈值和 Prometheus watchdog。

默认由 Docker 中的固定版本 k6 发压。若 Docker Desktop NAT 导致公网连接超时，可将 `K6_BINARY` 指向本机原生 k6 可执行文件；runner 会直接执行该文件，仍会剥离所有 `PROD_SSH_*` 环境变量，且不会把凭据写入命令或日志：

```powershell
$env:K6_BINARY = "C:\tools\k6\k6.exe"
python tests/load/run_production_load_test.py --dry-run --capacity-edge
python tests/load/run_production_load_test.py --capacity-edge
Remove-Item Env:K6_BINARY -ErrorAction SilentlyContinue
```

原生 edge 命令等价于 `k6 run --env CAPACITY_PROFILE=edge tests/load/k6-capacity-readonly.js`。离线检查 edge 配置时，也必须把 profile 作为 k6 CLI 参数传入，例如 `k6 inspect --env CAPACITY_PROFILE=edge tests/load/k6-capacity-readonly.js`；不要只设置容器环境变量后执行 `k6 inspect`，该子命令不会按 `k6 run` 的方式自动注入脚本环境。

容量 profile 每 15 秒检查生产 Prometheus：5xx 或非 2xx 超过 `1%` 立即停止，P95 超过 `0.5s` 连续 4 次、任一主机实例 CPU 超过 `70%` 或任一 Tomcat connector busy/max 超过 `60%` 时停止。SSH、Prometheus 或必需监控指标中断时仍按 fail-closed 停止。

完整 profile 的 summary 保存为 `tests/load/results/capacity-summary-<timestamp>.json`，edge profile 保存为 `capacity-edge-summary-<timestamp>.json`。runner 对应保存 `capacity-prometheus-<timestamp>.json` 或 `capacity-edge-prometheus-<timestamp>.json`，包含每 15 秒快照和各平台 Prometheus 最大 P95、CPU、Tomcat、5xx、非 2xx；Prometheus 使用 1 分钟滚动窗口，平台边界为 runner 侧近似时间。测试后仍可使用 `--report --window 30m` 生成按服务端 URI 聚合的补充报告。

所有目标值都是四接口混合场景的总 QPS。`2000 QPS` 表示四个接口合计约 2000 QPS、均分后每接口约 500 QPS，不代表任一单接口已验证 2000 QPS。

## 推荐：带生产保护的执行器

正式执行优先使用 `run_production_load_test.py`。它先通过 SSH 在生产机本地查询 `127.0.0.1:9091`，确认 `backend-actuator`、`node-exporter`、`cadvisor`、`alloy`、`loki` 全部为 UP，并检查 HTTP histogram、主机 CPU、Tomcat busy/max threads 指标确实存在。压测由本机 Docker 中的固定版本 `grafana/k6:0.54.0` 执行，JSON summary 仍保留在 `tests/load/results/`。

准备环境变量，秘密不会被执行器打印：

```powershell
$env:K6_ACCESS_TOKEN = "<本次临时 Bearer Token>"
$env:PROD_SSH_HOST = "<生产 SSH 主机>"
$env:PROD_SSH_USER = "<生产 SSH 用户>"
$env:PROD_SSH_PASSWORD = "<生产 SSH 密码>"
```

SSH 主机必须已写入当前用户的 `known_hosts`；执行器拒绝自动信任未知主机密钥。可先做完全离线的计划检查，再做只读生产预检：

```powershell
python tests/load/run_production_load_test.py --dry-run --smoke
python tests/load/run_production_load_test.py --preflight
```

预检通过后先冒烟，再执行完整阶梯压测：

```powershell
python tests/load/run_production_load_test.py --smoke
python tests/load/run_production_load_test.py --full
```

执行器每 15 秒读取一次生产 Prometheus。当 5xx 比例超过 1%、主机 CPU 超过 85% 或 Tomcat 线程使用率超过 80% 时立即中止；P95 超过 2 秒连续 4 次时中止。SSH 或 Prometheus 监控中断也会停止 k6 并返回非零退出码。Windows 上先发送 `CTRL_BREAK_EVENT` 让 k6 写完 summary，超时后才逐级终止进程。

## 生成压测观测报告

`--report` 只通过 SSH 查询生产机本地 Prometheus，不启动 Docker 或 k6，也不需要 `K6_ACCESS_TOKEN`。默认统计最近 15 分钟；`--window` 仅接受 `15m`、`1h` 这类正整数 Prometheus 时间格式。

```powershell
python tests/load/run_production_load_test.py --dry-run --report --window 15m
python tests/load/run_production_load_test.py --report --window 15m
```

报告按 `method + uri` 输出峰值 QPS、平均 QPS、最大 P95 和 5xx 增加量，并输出全局主机 CPU、Tomcat 线程使用率峰值。控制台显示简洁表格，完整脱敏 JSON 保存为 `tests/load/results/production-report-<timestamp>.json`。报告不包含 SSH 凭据、Bearer Token、手机号或验证码。
