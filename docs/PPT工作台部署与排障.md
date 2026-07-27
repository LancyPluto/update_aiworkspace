# PPT 工作台部署与排障

## 1. 生产链路

PPT 工作台只调用平台后端。Banana Slides 负责大纲、页面描述、视觉编排和 PPTX 导出，模型选择、供应商凭证、路由、计费与审计均由平台持有。

```text
user-web -> backend /api/v2/ppt -> Banana Slides
                                  -> platform model gateway
                                  -> worker -> text/image provider
```

生产 Banana 必须来自 `AI-miniLab/banana-slides` 产品 Fork，并以阿里云 ACR 的不可变 `@sha256:` digest 部署。ACR 仓库是公有只读，生产服务器匿名拉取固定 digest；只有发布镜像的 Fork 流水线或维护者需要 ACR 推送凭证。浏览器不得直接访问 Banana，也不得在 Banana 或用户项目中保存模型 Key。

## 2. 本地开发

```powershell
cd D:\code6\aidesu\.worktrees\ppt-workbench-v2
powershell -NoProfile -ExecutionPolicy Bypass `
  -File .\scripts\start-dev.ps1 `
  -StartInfra auto `
  -StartPpt auto `
  -ApplySql check
```

启动脚本依次检查基础设施、SQL、Banana readiness、后端、Worker 和前端，并输出历史资产与 Banana 中间文件目录。

- 平台历史资产：`D:/data/generated-media`
- Banana 本地数据：由 `BANANA_SLIDES_LOCAL_DATA_DIR` 指定
- Banana readiness：`http://127.0.0.1:5000/readyz`
- 用户端：`http://localhost:5173`

本地 Ofox 出网使用 `LOCAL_CONTAINER_PROXY_URL`。脚本可从 Windows 系统代理自动识别端口；容器地址必须使用 `host.docker.internal`，不能使用容器内的 `127.0.0.1`。生产统一使用 Compose 中的 `mihomo:7890`，不接受管理后台或用户请求覆盖代理地址。

## 3. 常见问题

### 3.1 大纲和页面描述显示 `ä¸º...` 乱码

根因是部分 OpenAI-compatible SSE 响应只返回 `text/event-stream`，未声明 `charset=UTF-8`。`requests.iter_lines(decode_unicode=True)` 会按 ISO-8859-1 解码，中文在 Worker 写库前已经损坏。

修复点：

1. Worker 对 SSE 行使用字节模式并显式按 UTF-8 解码。
2. 非 UTF-8 流立即失败，不保存半损坏内容。
3. 前端仅对可逆、合法 UTF-8 且恢复后 CJK 字符数量增加的历史 PPT 字符串做兼容恢复。

排查顺序：

```powershell
docker logs ai-supermarket-worker --since 30m 2>&1 |
  Select-String -Pattern "chat/completions|model stream|taskId"
```

然后比较 `ai_result_resources.content_text` 与 `banana_slides.pages.outline_content`。如果平台任务结果已经乱码，问题在模型 Worker；如果平台结果正常而 Banana 乱码，再检查平台 Provider 的 JSON 编解码。

### 3.2 图片阶段停在 `3/3`

`3/3` 表示三张图均已提交或完成，不代表代理端口。先查 Worker 是否记录供应商 HTTP 200，再查平台模型子任务、Banana 页面状态和 PPT 父任务轮询。供应商成功而界面不结束通常是状态回写、receipt 或租约推进问题，不是代理问题。

```powershell
docker logs ai-supermarket-worker --since 30m 2>&1 |
  Select-String -Pattern "ofox|transport succeeded|image generation task"
docker logs ai-supermarket-banana-slides --since 30m
```

### 3.3 Ofox 无法连接

```powershell
Get-NetTCPConnection -State Listen -LocalPort 7890,12000 -ErrorAction SilentlyContinue
docker inspect ai-supermarket-worker --format "{{range .Config.Env}}{{println .}}{{end}}" |
  Select-String -Pattern "PROJECT_MIHOMO_PROXY_URL|ALLOW_LOCAL_HOST_PROXY"
```

不要假定本机一定监听 `7890`。以实际代理进程端口为准，并重新创建 Worker 使环境变量生效。代理只能由部署环境配置，不能写入模型 `extraAuthJson`。

### 3.4 Banana 不健康或反复重启

检查 `/livez` 与 `/readyz`，再查看迁移容器和应用容器日志。运行期日志不应出现 `uv sync`、`pip install` 或 Alembic 迁移。MySQL 与 SQLite 参数必须由应用工厂按方言隔离。

### 3.5 历史文件不可见

平台资产目录与 Banana 中间目录是两套数据。确认 `GENERATED_MEDIA_DIR=D:/data/generated-media` 未被 Docker 匿名卷覆盖；Banana 上传与导出目录使用显式宿主机绑定。不要迁移、清空或重建历史资产目录。

## 4. 分支收口顺序

PPT 上线涉及主仓与产品 Fork，按以下顺序整理提交：

1. Banana Fork：平台 Provider、MySQL/receipt/恢复、容量限制、不可变运行时及测试。
2. 发布 Fork commit，构建并推送 ACR 镜像，取得不可变 digest。
3. 主仓后端：V2 API、模型路由、状态机、计费恢复、迁移与测试。
4. 主仓 Worker：平台代理执行、代理安全和 UTF-8 SSE 修复。
5. 主仓前端：模型选择、阶段导航、确认生成、历史乱码兼容和测试。
6. 主仓部署：Compose、版本锁、健康检查、生产冒烟、回滚和文档。

主仓创建 PR 前，`engines/versions.lock.json` 中的 Fork commit 必须等于产品 Fork 的已推送 HEAD，镜像必须属于批准的 ACR 仓库并使用 digest，`releaseStatus` 必须为 `READY`。

## 5. 一键 PR 发布入口

先执行完整本地门禁：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass `
  -File .\scripts\ppt-release.ps1 `
  -Mode Validate `
  -RefreshRemote
```

提交并确认两个工作树干净后，一键推送当前 `codex/*` 分支并创建面向 `dev` 的 PR：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass `
  -File .\scripts\ppt-release.ps1 `
  -Mode CreatePr `
  -RefreshRemote
```

脚本不会自动合并 PR，也不会从开发机直接改生产。PR 阶段只运行 CI；全部检查通过并人工合并到 `dev` 后，`dev-delivery.yml` 才在隔离的生产 Runner 上执行部署。

## 6. 自动部署与回滚

合并后的 `dev` push 执行：

1. Backend、Worker、前端和部署契约测试。
2. 校验生产凭据文件权限和必需字段，但不打印秘密；Banana 公有镜像拉取不需要 ACR 凭据。
3. 数据库备份与增量迁移。
4. 校验 Banana Fork commit、ACR digest、版本锁和模型能力池。
5. 拉取 Banana 远程镜像，重建变更服务并检查 readiness。
6. 使用专用账号生成三页 PPT，下载并验证 OOXML。
7. 冒烟成功后确认生产 revision；失败时执行现有回滚并关闭 PPT 新建/生成熔断。

生产必要凭据仅保存在 CD Runner 的 `0600` 文件中：SSH 和 `PPT_SMOKE_AUTH_TOKEN`。ACR 推送凭据属于 Fork 镜像发布侧，不进入主仓生产部署。仓库、Actions 输出和 Compose 文件不得包含这些值。

## 7. 上线验收

- 新用户无需配置 Key，可选择平台可执行文本与图片模型。
- 大纲、描述、三张视觉图和 PPTX 均成功，中文无乱码。
- 刷新后任务继续恢复；重复提交不重复计费。
- 两个用户并发时项目、模型、文件、receipt 和用量严格隔离。
- 下载结果包含 `[Content_Types].xml`、`ppt/presentation.xml` 和预期数量的 slide XML。
- `D:/data/generated-media` 中的历史资产仍可预览和下载。
