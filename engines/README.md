# PPT 引擎

本目录保存 PPT 渲染引擎的版本锁、API 契约、许可证快照和部署元数据。
引擎源码由独立 Fork 管理，不再复制进主仓库。

## 目录

```text
engines/
  contracts/         # 平台实际消费的窄 API 契约与响应夹具
  third-party/       # 第三方许可证与修改说明
  versions.lock.json # 上游 commit、Fork commit、镜像 digest 和能力版本
  README.md
```

用户界面只存在于 `user-web` 的 PPT 工作台。不要部署或公开 Banana
自带前端，浏览器也不得直接访问引擎。

## 与超市的关系

| 组件 | 职责 |
| --- | --- |
| `backend` `/api/v1/ppt/*` | 鉴权、算力、项目 binding、代理引擎 |
| Banana Slides 固定镜像 | 大纲 / 描述 / 出图 / 导出 |
| 管理端 `agent_model_configs` / vendor account | 模型、账户路由与 API Key 的唯一配置源 |
| PPT 平台模型桥 | 每次任务提交前解析执行凭证并同步到引擎 `/api/settings` |

用户**不**直接访问本引擎；仅内网或本机 `PPT_ENGINE_BASE_URL`。
PPT 不维护第二套模型配置：工作流显式绑定优先；未绑定时自动使用平台
启用的默认文本模型，并优先选择平台 GPT Image 2.0 生图配置。

## 本地开发

### 1. 准备固定镜像

```bash
export BANANA_SLIDES_IMAGE='registry.cn-hangzhou.aliyuncs.com/<namespace>/banana-slides@sha256:<digest>'
```

### 2. Docker 启动引擎（推荐）

在仓库根目录：

```bash
docker compose \
  -f deploy/docker-compose.yml \
  -f deploy/docker-compose.ppt.yml \
  up -d banana-slides backend
```

引擎不映射宿主机公网端口。backend 通过 Docker 内部服务名
`http://banana-slides:5000` 访问。

### 3. 启动超市 backend

```bash
cd backend
PPT_ENGINE_BASE_URL=http://127.0.0.1:5001 AGENT_ENABLED=false mvn spring-boot:run
```

宿主机单独调试时可以显式映射临时端口；生产 Compose 不开放该端口。

## 更新引擎

1. 在产品 Fork 中合并并审查上游 commit。
2. 运行 Fork 的测试和镜像构建。
3. 推送到阿里云 ACR，并取得不可变 digest。
4. 更新 `versions.lock.json`、契约夹具、LICENSE 和 NOTICE。
5. 使用 Compose 配置检查和后端契约测试验证后再部署。

`versions.lock.json` 中任一 Fork、许可证或镜像字段为空时，只表示开发契约，
不得作为生产发布依据。

## 相关文档

- `docs/PPT生成工具接入-后端开发文档.md`
- `docs/PPT生成工具接入-前端开发文档.md`
