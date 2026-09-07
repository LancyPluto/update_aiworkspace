# PPT 生成工具接入实施文档（基于当前 `ai-tool-market` 架构）

> **说明**：本文件为方案综述。开发实施请以分册为准：  
> - 后端：[PPT生成工具接入-后端开发文档.md](./PPT生成工具接入-后端开发文档.md)  
> - 前端：[PPT生成工具接入-前端开发文档.md](./PPT生成工具接入-前端开发文档.md)

更新时间：2026-05-22

## 1. 目标

将本地已跑通的开源 PPT 生成项目 `banana_slider/banana-slides` 接入 `ai-tool-market`，满足以下目标：

1. 保留 `banana-slides` 原有生成流程和能力边界。
2. 前端界面不复用原项目 React UI，由 `user-web` 自行设计新的业务界面。
3. 接入后仍纳入 `ai-tool-market` 的登录、算力、运营配置、审计与工具列表体系。
4. 不把 PPT 多阶段工作流硬塞进当前“动态表单 -> ai_tasks -> Redis -> worker”的标准单任务链路。

---

## 2. 先说结论：推荐接入方式

### 2.1 采用 `工具卡片 + 自定义工作台 + 后端 BFF + 外部引擎服务`

推荐结构：

- `ai-tool-market/backend`：新增 PPT BFF 模块，负责鉴权、用户隔离、算力扣费、项目绑定、代理调用 `banana-slides`
- `ai-tool-market/user-web`：新增 PPT 自定义工作台页面
- `ai-tool-market/admin-frontend`：继续用现有工具管理页配置工具卡片、封面、名称、预估算力
- `banana-slides/backend`：继续保留原有项目/页面/任务/导出 API，不重写其核心流程

### 2.2 为什么不要走当前标准 Worker 任务链路

当前超市稳定主链路是：

1. 管理后台配置工具和字段 schema
2. 用户端进入 `/tools/:id/use`
3. 动态表单提交到 `backend`
4. `backend` 创建 `ai_tasks`
5. Redis 投递给 `worker`
6. `worker` 单次执行后回写结果

这条链路适合“一次提交，一次产出”的工具，比如文案生成、图片生成。

但 `banana-slides` 是典型的**项目型、多步骤、可反复编辑**工具：

- 有项目级状态：`DRAFT`、`OUTLINE_GENERATED`、`DESCRIPTIONS_GENERATED`、`GENERATING_IMAGES`、`COMPLETED`
- 有子任务：描述生成、图片生成、单页重生成、翻新任务、导出任务
- 支持中途编辑大纲、编辑描述、单页重做、风格图上传、文件上传、PPT 翻新

如果强行改造成一个 `ai_task`：

- 难以表达中间态
- 难以支持单页重试和多轮编辑
- 会把 `banana-slides` 的编排逻辑复制到 `worker`
- 后续维护成本非常高

**因此最优方案是：保留 banana 的工作流，引入 BFF 适配层。**

---

## 3. 当前仓库现状与接入边界

## 3.1 `ai-tool-market` 当前稳定架构

当前仓库主链路已经比较清晰：

- `backend`：Spring Boot，负责认证、工具配置、任务、算力、计费
- `worker`：Python，负责消费 Redis 任务
- `user-web`：Vue 3，当前标准工具页面是工具详情页 + 动态表单页 + 任务状态页 + 任务结果页
- `admin-frontend`：Next.js，负责工具和模型管理

相关现状文件：

- 架构说明：`docs/系统架构与边界.md`
- 用户端标准工具路由：`user-web/src/router/index.ts`
- 标准工具详情页：`user-web/src/pages/ToolDetail/Page.vue`
- 标准工具使用页：`user-web/src/pages/ToolUse/Page.vue`
- 工具实体：`backend/src/main/java/com/aiminilab/aitoolmarket/tool/entity/AiTool.java`
- 工具管理接口：`backend/src/main/java/com/aiminilab/aitoolmarket/tool/controller/AdminToolController.java`

### 3.2 `banana-slides` 当前能力边界

`banana-slides` 不是简单的“生成一段结果”，而是一整套 PPT 项目服务：

- 创建项目：`POST /api/projects`
- 生成大纲：`POST /api/projects/{id}/generate/outline`
- 流式生成大纲：`POST /api/projects/{id}/generate/outline/stream`
- 生成全部描述：`POST /api/projects/{id}/generate/descriptions`
- 流式生成全部描述：`POST /api/projects/{id}/generate/descriptions/stream`
- 生成全部图片：`POST /api/projects/{id}/generate/images`
- 单页生成描述：`POST /api/projects/{id}/pages/{pageId}/generate/description`
- 单页生成图片：`POST /api/projects/{id}/pages/{pageId}/generate/image`
- 修改图片：`POST /api/projects/{id}/pages/{pageId}/edit/image`
- 修改大纲/描述：`PUT /api/projects/{id}/pages/{pageId}/outline`、`PUT /api/projects/{id}/pages/{pageId}/description`
- 翻新入口：`POST /api/projects/renovation`
- 导出：`GET /api/projects/{id}/export/pptx`、`/pdf`、`/images`

相关现状文件：

- `banana-slides/backend/controllers/project_controller.py`
- `banana-slides/backend/controllers/page_controller.py`
- `banana-slides/backend/controllers/export_controller.py`
- `banana-slides/backend/tests/integration/test_api_full_flow.py`

---

## 4. 接入总体方案

```text
user-web 自定义 PPT 工作台
        |
        v
backend /api/v1/ppt/*
        |
        v
banana-slides /api/projects/*
```

### 4.1 三层职责

#### A. `banana-slides`

保留：

- 项目创建和状态机
- 大纲/描述/图片生成
- 单页编辑与重生成
- 翻新、导出、素材文件处理

原则：

- 尽量不改核心业务逻辑
- 若要适配超市，只做少量“无害增强”，比如增加 traceId、补充状态字段、优化错误信息

#### B. `ai-tool-market/backend`

新增 PPT BFF 模块，职责：

- 校验当前登录用户
- 维护“超市用户 <-> banana project”绑定关系
- 将 `bindingId` 映射到 `banana_project_id`
- 转发请求到 `banana-slides`
- 按步骤冻结/结算算力
- 屏蔽 banana 内网地址
- 统一下载地址和错误码

#### C. `ai-tool-market/user-web`

新增 PPT 工作台，而不是走当前：

- `/tools/:id/use`
- `DynamicForm`
- `/tasks/:taskId/status`
- `/tasks/:taskId/result`

改为：

- 工具详情页点击“立即使用”进入 PPT 自定义工作台
- 工作台内部按项目状态切换：创建、编辑大纲、编辑描述、预览导出

---

## 5. 后端开发文档

## 5.1 数据库设计

建议新增一张最核心的绑定表：

```sql
CREATE TABLE ppt_project_bindings (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  tool_id BIGINT NOT NULL,
  banana_project_id VARCHAR(64) NOT NULL,
  creation_type VARCHAR(32) NOT NULL,
  title VARCHAR(255) NULL,
  status VARCHAR(64) NOT NULL DEFAULT 'DRAFT',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_banana_project_id (banana_project_id),
  KEY idx_user_tool (user_id, tool_id)
);
```

如需按步骤计费，再补：

```sql
CREATE TABLE ppt_step_billing_logs (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  binding_id BIGINT NOT NULL,
  step_code VARCHAR(64) NOT NULL,
  credits_charged INT NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_binding_step (binding_id, step_code)
);
```

建议步骤编码：

- `CREATE`
- `OUTLINE`
- `DESCRIPTIONS`
- `IMAGES`
- `EXPORT_PPTX`
- `EXPORT_PDF`
- `RENOVATION`

## 5.2 工具注册方式

PPT 工具仍然应注册到当前 `ai_tools` 表，不建议新建一套工具卡片体系。

原因：

- 当前工具列表、工具详情、封面图、上下架、预估算力都已经围绕 `ai_tools`
- `admin-frontend` 现有工具管理已经可用
- 对外只需要把“如何进入使用页”从标准 `ToolUse` 改成自定义工作台

建议配置：

- `toolCode`: `banana_ppt_generator`
- `toolName`: `AI PPT 生成器`
- `toolType`: 可先用 `IMAGE_GENERATION`，或者扩展一个 `PPT_GENERATION`
- `inputModality`: `MULTIMODAL`
- `outputModality`: `FILE`
- `status`: `ONLINE`
- `estimatedCreditCost`: 写总预估值，详细分步成本写入 `configNote`

建议在 `configNote` 补一段机器可读配置，例如：

```json
{
  "integrationMode": "PPT_WORKSPACE",
  "customUiRoute": "/tools/banana_ppt_generator/workspace"
}
```

前端即可据此跳转自定义页面，而不是 `/tools/:id/use`。

## 5.3 后端配置项

建议新增配置：

```yaml
ppt:
  engine:
    base-url: http://banana-slides:5000
    connect-timeout-ms: 5000
    read-timeout-ms: 300000
  billing:
    create-project-credits: 5
    generate-outline-credits: 10
    generate-descriptions-credits: 20
    generate-images-credits: 50
    export-pptx-credits: 5
    export-pdf-credits: 5
```

说明：

- `banana-slides` 模型 API Key 先继续保留在 banana 自己的配置体系里
- 不建议第一期强行接到 `ai-tool-market` 的 `model_config` 管理里
- 先把“用户访问、业务流程、计费”打通，再考虑统一模型配置中心

## 5.4 后端接口设计

统一前缀建议：`/api/v1/ppt`

### 项目接口

- `POST /api/v1/ppt/projects`
- `GET /api/v1/ppt/projects`
- `GET /api/v1/ppt/projects/{bindingId}`
- `DELETE /api/v1/ppt/projects/{bindingId}`
- `POST /api/v1/ppt/projects/renovation`

### 项目流程接口

- `POST /api/v1/ppt/projects/{bindingId}/template`
- `POST /api/v1/ppt/projects/{bindingId}/generate/outline`
- `POST /api/v1/ppt/projects/{bindingId}/generate/outline/stream`
- `POST /api/v1/ppt/projects/{bindingId}/generate/descriptions`
- `POST /api/v1/ppt/projects/{bindingId}/generate/descriptions/stream`
- `POST /api/v1/ppt/projects/{bindingId}/generate/images`
- `GET /api/v1/ppt/projects/{bindingId}/tasks/{taskId}`

### 页面编辑接口

- `PUT /api/v1/ppt/projects/{bindingId}/pages/{pageId}/outline`
- `PUT /api/v1/ppt/projects/{bindingId}/pages/{pageId}/description`
- `POST /api/v1/ppt/projects/{bindingId}/pages/{pageId}/generate/description`
- `POST /api/v1/ppt/projects/{bindingId}/pages/{pageId}/generate/image`
- `POST /api/v1/ppt/projects/{bindingId}/pages/{pageId}/edit/image`
- `POST /api/v1/ppt/projects/{bindingId}/refine/outline`
- `POST /api/v1/ppt/projects/{bindingId}/refine/descriptions`

### 导出接口

- `GET /api/v1/ppt/projects/{bindingId}/export/pptx`
- `GET /api/v1/ppt/projects/{bindingId}/export/pdf`
- `GET /api/v1/ppt/projects/{bindingId}/export/images`

### 文件代理接口

- `GET /api/v1/ppt/files/{bindingId}/...`

这个接口的意义是：

- 前端永远不直接访问 banana 的 `/files/*`
- 下载和图片预览都走超市同源域名
- 便于做权限校验和地址隐藏

## 5.5 后端实现细节

### 1. 所有请求先解 `bindingId`

流程：

1. 根据 `bindingId` 查 `ppt_project_bindings`
2. 校验 `user_id == 当前登录用户`
3. 取出 `banana_project_id`
4. 拼接 banana 真实接口地址

### 2. 统一 HTTP Client

建议新增：

- `PptEngineProperties`
- `PptEngineClient`
- `PptProjectService`
- `PptBillingService`
- `PptProjectController`

### 3. 计费策略

建议按“动作成功触发”计费，不按页面数实时拆账。

第一期简单做法：

- 创建项目前冻结 `CREATE`
- 生成大纲前冻结 `OUTLINE`
- 生成描述前冻结 `DESCRIPTIONS`
- 生成图片前冻结 `IMAGES`
- 导出前冻结 `EXPORT_*`

成功后结算，失败则释放。

### 4. 错误处理

需要把 banana 错误映射到超市统一错误码，例如：

- `PPT_PROJECT_NOT_FOUND`
- `PPT_ENGINE_ERROR`
- `PPT_EXPORT_FAILED`
- `PPT_TASK_FAILED`
- `CREDIT_NOT_ENOUGH`

### 5. 不要接 Redis Worker

这个模块默认**不创建 `ai_tasks`、不入 Redis、不过 `worker`**。

只有未来你们想做“我只填一个主题，后台全自动跑完再给我 PPT”时，才考虑加一个薄封装任务入口。

---

## 6. 前端开发文档

## 6.1 路由改造

当前标准工具使用路径是：

- 工具详情：`/tools/:id`
- 标准使用页：`/tools/:id/use`

PPT 接入后建议新增：

- `/tools/banana_ppt_generator/workspace`
- `/tools/banana_ppt_generator/workspace/:bindingId`

分别承担：

- `workspace`：项目列表 + 新建入口
- `workspace/:bindingId`：项目编辑工作台

## 6.2 工具详情跳转逻辑

在 `user-web/src/pages/ToolDetail/Page.vue` 中，当前“开始使用”默认跳 `/tools/:id/use`。

这里要改成：

- 若 `toolCode === 'banana_ppt_generator'`
- 或 `configNote.integrationMode === 'PPT_WORKSPACE'`
- 则跳转到自定义工作台

这样不会影响其他普通工具。

## 6.3 页面结构建议

建议新增目录：

```text
user-web/src/pages/PptWorkspace/
  Page.vue
  Editor.vue
  components/
    CreateIdeaForm.vue
    CreateOutlineForm.vue
    CreateDescriptionForm.vue
    RenovationUpload.vue
    ProjectCard.vue
  steps/
    OutlineStep.vue
    DescriptionStep.vue
    PreviewStep.vue
    ExportStep.vue
  composables/
    usePptApi.ts
    usePptProject.ts
    usePptTask.ts
```

## 6.4 页面信息架构

### A. 工作台首页

内容：

- 我的 PPT 项目列表
- 新建入口 Tab：
  - 从想法创建
  - 从大纲创建
  - 从描述创建
  - 上传 PPT/PDF 翻新

### B. 编辑页

建议做四个主步骤：

1. 大纲编辑
2. 描述编辑
3. 预览出图
4. 导出下载

### C. 状态驱动渲染

根据 `project.status` 决定默认聚焦步骤：

- `DRAFT` -> 创建完成，等待大纲
- `OUTLINE_GENERATED` -> 进入描述阶段
- `DESCRIPTIONS_GENERATED` -> 进入图片阶段
- `GENERATING_IMAGES` -> 展示任务进度
- `COMPLETED` -> 展示导出区

## 6.5 前端 API 封装建议

新增 `user-web/src/api/pptApi.ts`，不要把 PPT API 混到现有 `toolApi.ts` 或 `aiToolApi.ts`。

核心方法建议：

- `createPptProject`
- `createPptRenovationProject`
- `listPptProjects`
- `fetchPptProject`
- `deletePptProject`
- `generateOutline`
- `generateOutlineStream`
- `generateDescriptions`
- `generateDescriptionsStream`
- `generateImages`
- `pollPptTask`
- `updatePageOutline`
- `updatePageDescription`
- `generatePageDescription`
- `generatePageImage`
- `editPageImage`
- `refineOutline`
- `refineDescriptions`
- `exportPptx`
- `exportPdf`
- `exportImages`

## 6.6 前端轮询与流式策略

### 大纲/描述

banana 已支持 SSE：

- `generate/outline/stream`
- `generate/descriptions/stream`

建议：

- 第一版直接使用普通接口 + 轮询即可上线
- 第二版再接入流式，提升体验

### 图片生成 / 导出异步任务

继续用轮询：

- 每 2~3 秒查询一次 `tasks/{taskId}`
- 终态 `COMPLETED` / `FAILED` 停止

## 6.7 UI 设计建议

你们既然想自己设计前端，建议重点保留能力，不保留原始 UI 结构。

推荐保留的交互能力：

- 创建入口分类清晰
- 大纲支持拖拽排序
- 描述区支持单页编辑和批量重生成
- 预览区支持单页重绘和局部改图
- 导出区支持 PPTX / PDF / 图片包
- 项目列表可查看最近编辑时间和状态

推荐复用超市现有 UI 能力：

- 登录态
- 页面壳子 `AppShell`
- 统一错误提示
- 算力不足提示
- 状态标签风格

---

## 7. 推荐实施顺序

## Phase 1：最小可用闭环

目标：从“想法创建”走到“导出 PPTX”

内容：

1. 管理后台配置 `banana_ppt_generator`
2. backend 增加 `ppt_project_bindings`
3. backend 实现创建项目、查详情、生成大纲、生成描述、生成图片、导出 PPTX
4. user-web 实现工作台首页 + 编辑页基本版

验收：

- 用户可在超市卡片进入 PPT 工作台
- 可完整走通：创建 -> 大纲 -> 描述 -> 图片 -> 导出

## Phase 2：补齐编辑能力

内容：

1. 单页编辑大纲
2. 单页编辑描述
3. 单页重生成描述
4. 单页重生成图片
5. `refine/outline`
6. `refine/descriptions`

验收：

- 用户可以在中间态编辑，不必重建整个项目

## Phase 3：补齐高级能力

内容：

1. 模板图上传
2. 流式大纲/流式描述
3. PPT 翻新入口
4. 导出 PDF / 图片包
5. 文件代理和同源下载

## Phase 4：运营与计费完善

内容：

1. 分步扣费日志
2. 失败释放算力
3. 监控与 traceId
4. 后台文案优化

---

## 8. 这次接入哪些模块要改，哪些不要动

### 必改

- `backend`：新增 PPT BFF 模块
- `user-web`：新增 PPT 工作台和路由
- `admin-frontend`：新增一条工具配置数据，必要时补 `configNote`
- `sql` / `schema-test.sql` / `DataInitializer`：补绑定表与测试结构

### 尽量不改

- `worker` 主链路
- 现有普通工具的动态表单机制
- `banana-slides` 核心生成逻辑

### 不建议做

- 把 PPT 项目流强行改造成一个标准 `ai_task`
- 让浏览器直接请求 banana 服务
- 第一期就把 banana 模型配置改成完全跟随超市模型中心

---

## 9. 联调检查清单

### 后端

- [ ] `banana-slides` 服务仅内网可访问
- [ ] `ppt.engine.base-url` 可连通
- [ ] `bindingId` 均做用户权限校验
- [ ] 失败时能正确释放冻结算力
- [ ] banana 返回 `/files/*` 时已转换为超市同源地址

### 前端

- [ ] 工具详情页已对 PPT 工具特殊跳转
- [ ] 工作台首页支持 4 种创建入口
- [ ] 编辑页能正确响应项目状态切换
- [ ] 任务轮询和失败提示完整
- [ ] 导出按钮走 BFF 地址，不暴露 banana 域名

### 全链路

- [ ] 想法创建全链路跑通
- [ ] 单页改描述后可重新出图
- [ ] 项目只能被创建者访问
- [ ] 删除项目后 binding 同步清理

---

## 10. 最终建议

如果你们接下来要陆续接多个开源 AI 工具，建议把本次 PPT 接入沉淀成一种“**复杂工具接入模式**”：

- 简单工具：继续走当前 `DynamicForm + ai_tasks + worker`
- 复杂工具：走 `工具卡片 + 自定义工作台 + backend BFF + 外部引擎`

这样后面接类似：

- AI 长流程视频工具
- AI 文档分析工作台
- AI 设计编辑器

都可以复用同一类接入范式，而不是每次都试图往 `worker` 主链路里硬塞。
