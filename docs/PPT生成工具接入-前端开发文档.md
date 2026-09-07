# PPT 生成工具接入 — 前端开发文档

> **版本**：2.0  
> **更新日期**：2026-05-22  
> **读者**：用户端（user-web）、管理端（admin-frontend）前端  
> **原则**：**增量接入，不改动标准工具与 Agent 既有页面行为**

---

## 1. 文档目标

在 AI 工具超市中接入 PPT 生成能力：

1. **用户端**：自研多步工作台 UI（不复用 banana 原前端，不复用 `ToolUse` + `DynamicForm`）。
2. **管理端**：在现有「AI 工具管理」上配置 PPT 工作流（入口、步骤算力、功能开关）。
3. **不影响** 现有工具使用页、任务页、Chat、Agent 等模块。

---

## 2. 不影响原有功能的硬性边界

### 2.1 用户端（user-web）禁止改动行为

| 模块 | 路径 | 要求 |
| --- | --- | --- |
| 标准工具使用 | `pages/ToolUse/Page.vue` | **不修改** 创建任务逻辑；仅允许 ToolDetail 增加分支跳转 |
| 动态表单 | `components/DynamicForm/*` | 不修改 |
| 任务状态/结果 | `pages/TaskStatus/`、`pages/TaskResult/` | 不修改；PPT **不进入** 这些路由 |
| 任务 API | `api/taskApi.ts` | 不修改；PPT 使用独立 `pptApi.ts` |
| Chat | `pages/Chat/*` | 不修改 |
| Agent | `pages/AgentHome/*` | 不修改 |
| 工具列表 | `pages/ToolList/`、`pages/Dashboard/` | 不修改列表 API；Dashboard 可选后续按 toolCode 跳转，**非必须** |

### 2.2 用户端允许的最小改动

| 文件 | 改动 |
| --- | --- |
| `router/index.ts` | **新增** 2 条 PPT 路由 |
| `router/userRoutes.ts` | **新增** pptWorkspace 辅助方法 |
| `pages/ToolDetail/Page.vue` | 「立即使用」按钮：PPT 工具跳工作台，**其他工具仍跳 `/tools/:id/use`** |
| `api/types.ts` | **新增** PPT 相关类型与错误码（不删改既有类型） |
| `api/index.ts` | export `pptApi` |

### 2.3 管理端（admin-frontend）边界

| 模块 | 要求 |
| --- | --- |
| `app/tools/page.tsx` | 不破坏现有工具新建/编辑/字段 schema 流程 |
| 字段编辑器 | PPT 工具 **可不配置 fields** 或仅保留占位 |
| 增量 | P1 增加「PPT 工作流」Tab 或独立子页；P0 用 `configNote` 手写 JSON |

### 2.4 回归验证清单

- [ ] 任意非 PPT 工具：详情 → 使用 → 创建任务 → 状态 → 结果 正常
- [ ] Chat、Agent、我的任务 页面正常
- [ ] 管理端编辑小红书等工具、保存字段 schema 正常
- [ ] 未上架 PPT 工具时，超市无 PPT 入口或提示下架

---

## 3. 总体架构（前端视角）

```text
ToolList / ToolDetail（既有）
        │
        ├─ toolCode !== banana_ppt_generator ──► /tools/:id/use（既有，不变）
        │
        └─ toolCode === banana_ppt_generator ──► /tools/banana_ppt_generator/workspace（新增）

PptWorkspace（新增）
        │
        └─ 调用 /api/v1/ppt/*（pptApi.ts）
```

数据来源：

- 工具元数据：`GET /api/v1/tools/{toolCode}`（封面、名称、算力、**workflow**）
- 项目数据：`/api/v1/ppt/projects*`

---

## 4. 用户端开发（user-web）

### 4.1 路由

在 `src/router/index.ts` **追加**（不要改已有路由的 name/path）：

```ts
{
  path: "/tools/banana_ppt_generator/workspace",
  name: "PptWorkspace",
  meta: { requiresAuth: true },
  component: () => import("@/pages/PptWorkspace/Page.vue"),
},
{
  path: "/tools/banana_ppt_generator/workspace/:bindingId",
  name: "PptProjectEditor",
  meta: { requiresAuth: true },
  component: () => import("@/pages/PptWorkspace/Editor.vue"),
  props: true,
},
```

`router/userRoutes.ts` 增加：

```ts
pptWorkspace() {
  return { name: "PptWorkspace" }
},
pptProjectEditor(bindingId: string) {
  return { name: "PptProjectEditor", params: { bindingId } }
},
```

### 4.2 ToolDetail 跳转（唯一侵入点）

`pages/ToolDetail/Page.vue` 中，将「开始使用」链接从写死的 `/tools/:id/use` 改为计算属性：

```ts
import { computed } from "vue"
import { userRoutes } from "@/router/userRoutes"

const isPptWorkspaceTool = computed(() => {
  const code = tool.value?.toolCode
  if (code === "banana_ppt_generator") return true
  // 可选：解析 configNote 中 integrationMode === 'PPT_WORKSPACE'
  return false
})

const useLink = computed(() => {
  if (isPptWorkspaceTool.value) {
    return { name: "PptWorkspace" }
  }
  return userRoutes.toolUse(props.id)
})
```

模板：

```vue
<RouterLink
  v-if="!isOffline"
  :to="useLink"
  class="..."
>
  开始使用
</RouterLink>
```

**除上述外，ToolDetail 其他 Tab/展示逻辑不变。**

### 4.3 目录结构

```text
src/pages/PptWorkspace/
  Page.vue                 # 项目列表 + 创建入口
  Editor.vue               # 单项目编辑器（按 status 切换步骤）
  components/
    ProjectCard.vue
    CreateIdeaForm.vue
    CreateOutlineForm.vue
    CreateDescriptionsForm.vue
    RenovationUpload.vue
    StepNav.vue              # 大纲 | 描述 | 预览 | 导出
  steps/
    OutlineStep.vue
    DescriptionsStep.vue
    PreviewStep.vue
    ExportStep.vue
  composables/
    usePptApi.ts             # 薄封装，调 pptApi
    usePptProject.ts         # 项目详情加载与刷新
    usePptTask.ts            # 异步 task 轮询
```

### 4.4 API 模块 `src/api/pptApi.ts`

**新建文件**，不要合并进 `toolApi.ts` / `taskApi.ts`。

```ts
import { apiRequest, ApiBusinessError } from "./client"

export interface PptWorkflowStep {
  code: string
  name: string
  credits: number
  enabled: boolean
}

export interface PptWorkflow {
  integrationMode: string
  customUiRoute: string
  creationTypes: string[]
  steps: PptWorkflowStep[]
  features: Record<string, boolean>
}

export interface PptProjectSummary {
  bindingId: number
  title?: string
  status: string
  creationType: string
  pageCount?: number
  updatedAt: string
}

export interface PptProjectDetail {
  bindingId: number
  projectId: string
  status: string
  creationType: string
  imageAspectRatio?: string
  pages: PptPage[]
}

export interface PptPage {
  id: string
  orderIndex: number
  status: string
  outlineContent?: { title?: string; points?: string[] }
  descriptionContent?: { text?: string }
  generatedImageUrl?: string
}

export function createPptProject(body: Record<string, unknown>, token?: string) {
  return apiRequest<{ bindingId: number; projectId: string; status: string }>(
    "POST",
    "/api/v1/ppt/projects",
    { body, token },
  )
}

export function listPptProjects(token?: string) {
  return apiRequest<{ projects: PptProjectSummary[] }>("GET", "/api/v1/ppt/projects", { token })
}

export function fetchPptProject(bindingId: string, token?: string) {
  return apiRequest<PptProjectDetail>("GET", `/api/v1/ppt/projects/${bindingId}`, { token })
}

export function generateOutline(bindingId: string, body?: Record<string, unknown>, token?: string) {
  return apiRequest("POST", `/api/v1/ppt/projects/${bindingId}/generate/outline`, { body, token })
}

export function generateDescriptions(bindingId: string, token?: string) {
  return apiRequest<{ taskId: string }>(
    "POST",
    `/api/v1/ppt/projects/${bindingId}/generate/descriptions`,
    { token },
  )
}

export function generateImages(bindingId: string, body?: Record<string, unknown>, token?: string) {
  return apiRequest<{ taskId: string }>(
    "POST",
    `/api/v1/ppt/projects/${bindingId}/generate/images`,
    { body, token },
  )
}

export function pollPptTask(bindingId: string, taskId: string, token?: string) {
  return apiRequest<{ status: string; progress?: Record<string, unknown>; errorMessage?: string }>(
    "GET",
    `/api/v1/ppt/projects/${bindingId}/tasks/${taskId}`,
    { token },
  )
}

export function exportPptx(bindingId: string, token?: string) {
  return apiRequest<{ downloadUrl: string }>(
    "GET",
    `/api/v1/ppt/projects/${bindingId}/export/pptx`,
    { token },
  )
}

// ... 其余 PUT/POST/refine/renovation/delete 按后端文档补齐
```

`api/index.ts` 增加：`export * from "./pptApi"`

### 4.5 类型与错误码

`api/types.ts` 的 `ApiErrorCode` **追加**（不删旧值）：

```ts
| "PPT_PROJECT_NOT_FOUND"
| "PPT_STEP_DISABLED"
| "PPT_ENGINE_ERROR"
| "PPT_TASK_FAILED"
| "PPT_EXPORT_FAILED"
```

`ToolDetail` 若后端返回 `workflow`，可扩展 `ToolDetail` 接口：

```ts
workflow?: import("./pptApi").PptWorkflow
```

### 4.6 页面逻辑说明

#### `PptWorkspace/Page.vue`

- 布局：复用 `AppShell`
- 拉取 `listPptProjects`
- 拉取 `fetchToolByCode('banana_ppt_generator')` 获取 `workflow`
- Tab 创建入口：根据 `workflow.creationTypes` 显示/隐藏
- 创建成功 → `router.push(pptProjectEditor(bindingId))`

#### `PptWorkspace/Editor.vue`

- `props.bindingId`
- `usePptProject(bindingId)` 加载详情
- 顶部 `StepNav`：大纲 / 描述 / 预览 / 导出
- 默认步骤由 `project.status` 推导：

| status | 默认 Tab |
| --- | --- |
| `DRAFT` | 大纲（提示先生成大纲） |
| `OUTLINE_GENERATED` | 描述 |
| `DESCRIPTIONS_GENERATED` | 预览 |
| `COMPLETED` | 导出 |

#### `usePptTask.ts`（轮询）

```ts
export function usePptTask(bindingId: Ref<string>) {
  const polling = ref(false)
  let timer: ReturnType<typeof setInterval> | null = null

  async function waitForTask(taskId: string, token: string) {
    polling.value = true
    return new Promise<void>((resolve, reject) => {
      const tick = async () => {
        try {
          const res = await pollPptTask(bindingId.value, taskId, token)
          if (res.status === "COMPLETED") {
            stop()
            resolve()
          } else if (res.status === "FAILED") {
            stop()
            reject(new Error(res.errorMessage || "任务失败"))
          }
        } catch (e) {
          stop()
          reject(e)
        }
      }
      timer = setInterval(tick, 3000)
      tick()
    })
  }

  function stop() {
    polling.value = false
    if (timer) clearInterval(timer)
  }

  return { polling, waitForTask, stop }
}
```

#### 算力与步骤禁用

按钮点击前读取 `workflow.steps`：

- `enabled === false` → 禁用并提示
- 展示 `step.credits`（与详情页 `estimatedCreditCost` 一致风格）

错误处理复用 `ApiBusinessError`：

- `CREDIT_NOT_ENOUGH` → 引导 `/billing`
- `PPT_STEP_DISABLED` → 提示功能已关闭
- `TOOL_OFFLINE` → 返回工具列表

#### 下载与图片 URL

引擎返回的图片路径经 BFF 改写为 `/api/v1/ppt/files/...` 或同源路径；**不要** 配置 `VITE_*` 直连 banana `:5000`。

`vite.config.ts` 已有 `/api` 代理时，无需新增代理。

### 4.7 与工作流配置联动（必读）

| 配置来源 | 前端用法 |
| --- | --- |
| `GET /api/v1/tools/banana_ppt_generator` → `workflow` | 创建 Tab、按钮禁用、算力文案 |
| `estimatedCreditCost` | 详情页展示「约 X 算力」 |
| `status === OFFLINE` | 详情页已有下架提示；工作台路由 guard 可二次校验 |

**不要** 在前端写死步骤算力；以 `workflow.steps` 为准。

### 4.8 实施阶段（用户端）

| 阶段 | 内容 |
| --- | --- |
| P0 | 路由 + ToolDetail 跳转 + Page/Editor + create/outline/descriptions/images/export + 轮询 |
| P1 | 翻新、单页编辑、refine、PDF 导出、错误态优化 |
| P2 | SSE 流式大纲/描述 |

---

## 5. 管理端开发（admin-frontend）

### 5.1 P0：不改 UI，用现有工具表单

路径：`admin-frontend/app/tools/page.tsx`（已有工具 CRUD）

新建工具时填写 §后端文档 中的 `tool_code`、模态、封面、`estimated_credit_cost`。

在 **配置说明 `configNote`** 文本框粘贴：

```text
【运营说明】支持从想法/大纲/描述创建 PPT，支持 PPT 翻新（见工作流配置）。

<!-- ppt-workflow:{"integrationMode":"PPT_WORKSPACE","customUiRoute":"/tools/banana_ppt_generator/workspace","creationTypes":["idea","outline","descriptions","ppt_renovation"],"steps":[{"code":"CREATE","name":"创建项目","credits":5,"enabled":true},{"code":"OUTLINE","name":"生成大纲","credits":10,"enabled":true},{"code":"DESCRIPTIONS","name":"生成描述","credits":20,"enabled":true},{"code":"IMAGES","name":"生成图片","credits":50,"enabled":true},{"code":"EXPORT_PPTX","name":"导出PPTX","credits":5,"enabled":true}],"features":{"renovation":true,"singlePageRegenerate":true,"refineOutline":true,"refineDescriptions":true}} -->
```

保存走现有 `createTool` / `updateTool`，**不修改** `lib/api/tools.ts` 接口签名。

字段 schema Tab：**留空** 或一条占位说明字段（用户端不会走 DynamicForm）。

### 5.2 P1：PPT 工作流配置 UI（推荐）

在工具编辑 Dialog 中，当满足：

```ts
form.toolCode === "banana_ppt_generator" ||
parsedWorkflow?.integrationMode === "PPT_WORKSPACE"
```

显示额外 Tab **「PPT 工作流」**：

| 表单项 | 绑定 |
| --- | --- |
| 创建入口 | `creationTypes` 多选 |
| 步骤表格 | `steps[].code / name / credits / enabled` |
| 功能开关 | `features` 各 Switch |
| 引擎地址（只读） | 展示 `ppt.engine.base-url` 或文档链接 |

保存时调用：

- 继续 `updateTool` 基础字段
- 将 workflow 序列化为 `<!-- ppt-workflow:... -->` 写入 `configNote`（可复用 Chat 的 `serializeConfigNote` 模式，改为 `ppt-workflow` 标记）

参考现有解析模式（Chat 已实现）：

```261:288:admin-frontend/app/tools/page.tsx
function extractFrontendStyle(configNote?: string | null): { note: string; style: FrontendStyleConfig } {
  const raw = configNote || ""
  const match = raw.match(FRONTEND_STYLE_PATTERN)
  // ...
}

function serializeConfigNote(note: string, style: FrontendStyleConfig): string {
  // ...
}
```

建议新增 `extractPptWorkflow` / `serializePptWorkflow`，**不要改动** `ai-tool-ui` 相关函数。

### 5.3 P1：可选 Admin API

若后端实现 `GET/PUT /api/admin/v1/tools/{id}/ppt-workflow`：

- 新建 `lib/api/ppt-workflow.ts`
- 工作流 Tab 加载/保存走专用接口，减少手工 JSON 错误

### 5.4 管理端操作手册（给运营）

| 操作 | 步骤 |
| --- | --- |
| 上架 PPT | 工具列表 → 编辑 → 状态上架 |
| 下架 | offline，用户详情显示已下架 |
| 关闭翻新 | 工作流 `creationTypes` 去掉 `ppt_renovation` |
| 调价 | 修改对应 `step.credits` 与总 `estimatedCreditCost` |
| 临时关闭「生成图片」 | `IMAGES.enabled = false` |

### 5.5 管理端边界

- **不要** 要求 PPT 工具配置 `modelConfigId` 才能上架（除非产品明确要求）
- **不要** 修改 `FieldSchemaEditor` 的通用校验逻辑（避免影响其他工具「核心字段」规则）

---

## 6. UI/UX 建议（自研界面）

与 banana 原 UI 脱钩，建议：

| 区域 | 建议 |
| --- | --- |
| 视觉 | 延续超市 `AppShell`、Tailwind 变量、主色按钮 |
| 创建页 | 四 Tab 清晰；支持 16:9 等比例选择 |
| 大纲 | 列表可编辑标题/要点；支持拖拽排序（调 `PUT project` 的 `pages_order`） |
| 描述 | 左页码右编辑区；批量生成时全页 loading |
| 预览 | 缩略图网格；单页「重新生成」「改图」 |
| 导出 | 主按钮 PPTX；次要 PDF/图片包 |

---

## 7. 环境变量

用户端 **无需** 新增 `VITE_PPT_ENGINE_URL`。所有请求走 `/api/v1/ppt`。

管理端继续 `NEXT_PUBLIC_*` 指向 backend 即可。

---

## 8. 联调检查清单

### 用户端

- [ ] 非 PPT 工具「开始使用」仍进 `/tools/:id/use`
- [ ] PPT 工具进 `/tools/banana_ppt_generator/workspace`
- [ ] 想法 → 大纲 → 描述 → 图片 → 导出 PPTX 全流程
- [ ] 算力不足、下架、步骤关闭 提示正确
- [ ] 刷新 Editor 页状态与后端一致

### 管理端

- [ ] 保存 PPT 工具后 user-web 能读到 workflow（或 configNote 解析正确）
- [ ] 编辑其他工具 fields 不受影响

---

## 9. 相关文档

- 后端实现：`docs/PPT生成工具接入-后端开发文档.md`
- 架构边界：`docs/系统架构与边界.md`
