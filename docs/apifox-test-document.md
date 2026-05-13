# AI Tool Market — Apifox 测试文档

> 基于成员5测试文档 + 代码中实际接口整理
> 测试环境：`http://localhost:8080`

---

## 目录

1. [测试账号](#1-测试账号)
2. [后端 API 测试](#2-后端-api-测试)
3. [用户前端测试](#3-用户前端测试)
4. [管理后台测试](#4-管理后台测试)
5. [Worker 测试](#5-worker-测试)
6. [全链路测试场景](#6-全链路测试场景)
7. [测试账号管理](#7-测试账号管理)

---

## 1. 测试账号

| 账号 | 密码 | 角色 | 说明 |
|------|------|------|------|
| `user1` | `123456` | 普通用户 | 默认算力 100 |
| `admin` | `123456` | 管理员 | 可管理工具/用户/任务 |
| `disabled_user` | `123456` | 禁用用户 | 预期不能登录 |

---

## 2. 后端 API 测试

### 2.1 健康检查

```
GET /api/health
```

**响应：**
```json
{
  "code": "SUCCESS",
  "data": { "status": "UP" }
}
```

---

### 2.2 用户认证

#### 2.2.1 用户登录

```
POST /api/v1/auth/login
Content-Type: application/json

{
  "account": "user1",
  "password": "123456"
}
```

**成功响应：**
```json
{
  "code": "SUCCESS",
  "data": {
    "token": "eyJhbGciOiJIUzI1NiJ9...",
    "user": {
      "id": 1,
      "account": "user1",
      "nickname": "普通用户",
      "role": "USER"
    }
  }
}
```

**Apifox 后置操作：** 将 `data.token` 设为环境变量 `userToken`。

#### 2.2.2 获取当前用户信息

```
GET /api/v1/auth/me
Authorization: Bearer {{userToken}}
```

#### 2.2.3 用户退出

```
POST /api/v1/auth/logout
Authorization: Bearer {{userToken}}
```

**测试用例：**

| 用例 | 操作 | 期望结果 |
|------|------|---------|
| AUTH-001 | user1/123456 登录 | 返回 token |
| AUTH-002 | user1/wrongpass 登录 | 返回 401 错误 |
| AUTH-003 | disabled_user/123456 登录 | 返回 403 禁用错误 |
| AUTH-004 | 无 token 访问 /api/v1/me | 返回 401 |
| AUTH-005 | user1 token 访问管理接口 | 返回 403 |

---

### 2.3 管理员认证

#### 2.3.1 管理员登录

```
POST /api/admin/v1/auth/login
Content-Type: application/json

{
  "account": "admin",
  "password": "123456"
}
```

**Apifox 后置操作：** 将 `data.token` 设为环境变量 `adminToken`。

#### 2.3.2 获取管理员信息

```
GET /api/admin/v1/auth/me
Authorization: Bearer {{adminToken}}
```

#### 2.3.3 管理员退出

```
POST /api/admin/v1/auth/logout
Authorization: Bearer {{adminToken}}
```

**测试用例：**

| 用例 | 操作 | 期望结果 |
|------|------|---------|
| AUTH-006 | admin/123456 登录 | 返回管理员 token |
| AUTH-005 | user1 的 token 访问 `/api/admin/v1/...` | 返回 403 |

---

### 2.4 工具管理（管理员）

#### 2.4.1 创建工具

```
POST /api/admin/v1/tools
Authorization: Bearer {{adminToken}}
Content-Type: application/json

{
  "toolName": "小红书文案生成器",
  "toolCode": "xiaohongshu_copywriting",
  "categoryId": 1,
  "description": "生成小红书风格的种草文案",
  "toolIcon": "📝",
  "status": "DRAFT"
}
```

#### 2.4.2 获取工具列表（管理员）

```
GET /api/admin/v1/tools
Authorization: Bearer {{adminToken}}
```

#### 2.4.3 更新工具

```
PUT /api/admin/v1/tools/{toolId}
Authorization: Bearer {{adminToken}}
Content-Type: application/json

{
  "toolName": "小红书文案生成器 V2",
  "description": "更新后的描述"
}
```

#### 2.4.4 发布工具

```
POST /api/admin/v1/tools/{toolId}/publish
Authorization: Bearer {{adminToken}}
```

**前提条件：** 必须有 field schema 和 prompt 配置。

#### 2.4.5 下架工具

```
POST /api/admin/v1/tools/{toolId}/offline
Authorization: Bearer {{adminToken}}
```

#### 2.4.6 工具字段 Schema 管理

```
GET /api/admin/v1/tools/{toolId}/field-schemas
Authorization: Bearer {{adminToken}}
```

```
POST /api/admin/v1/tools/{toolId}/field-schemas
Authorization: Bearer {{adminToken}}
Content-Type: application/json

{
  "schemaVersion": "1.0",
  "items": [
    {
      "fieldCode": "productName",
      "fieldName": "产品名称",
      "fieldType": "TEXT",
      "required": true,
      "sortOrder": 1,
      "placeholder": "请输入产品名称"
    },
    {
      "fieldCode": "targetCustomer",
      "fieldName": "目标客户",
      "fieldType": "TEXT",
      "required": true,
      "sortOrder": 2,
      "placeholder": "例如：年轻女性"
    },
    {
      "fieldCode": "style",
      "fieldName": "文案风格",
      "fieldType": "SELECT",
      "required": true,
      "sortOrder": 3,
      "options": [
        { "label": "种草", "value": "种草" },
        { "label": "测评", "value": "测评" },
        { "label": "教程", "value": "教程" }
      ]
    }
  ]
}
```

```
POST /api/admin/v1/field-schemas/{schemaId}/publish
Authorization: Bearer {{adminToken}}
```

#### 2.4.7 Prompt 管理

```
GET /api/admin/v1/tools/{toolId}/prompts
Authorization: Bearer {{adminToken}}
```

```
POST /api/admin/v1/tools/{toolId}/prompts
Authorization: Bearer {{adminToken}}
Content-Type: application/json

{
  "promptCode": "main",
  "promptName": "主 Prompt"
}
```

```
POST /api/admin/v1/prompts/{promptId}/versions
Authorization: Bearer {{adminToken}}
Content-Type: application/json

{
  "versionNo": "1.0",
  "content": "你是一个专业小红书文案助手。\n\n请根据 {{productName}} 为 {{targetCustomer}} 生成一篇 {{style}} 风格文案。"
}
```

```
POST /api/admin/v1/prompt-versions/{versionId}/publish
Authorization: Bearer {{adminToken}}
```

```
POST /api/admin/v1/prompt-versions/{versionId}/test-generate
Authorization: Bearer {{adminToken}}
Content-Type: application/json

{
  "productName": "五一护理套餐",
  "targetCustomer": "年轻女性",
  "style": "种草"
}
```

**测试用例：**

| 用例 | 操作 | 期望结果 |
|------|------|---------|
| TOOL-001 | 创建工具（状态 DRAFT） | 创建成功 |
| TOOL-002 | 没有 field schema 时尝试发布 | 发布失败，提示缺少字段配置 |
| TOOL-003 | 没有 prompt 时尝试发布 | 发布失败，提示缺少 Prompt 配置 |
| TOOL-004 | 发布成功后用户端能查到此工具 | 用户端 GET /api/v1/tools 返回该工具 |
| TOOL-005 | 下架后用户端查不到此工具 | 用户端 GET /api/v1/tools 不再返回 |

---

### 2.5 工具（用户端）

#### 2.5.1 工具分类

```
GET /api/v1/tool-categories
```

#### 2.5.2 获取已发布工具列表

```
GET /api/v1/tools
Authorization: Bearer {{userToken}}
```

#### 2.5.3 获取工具详情

```
GET /api/v1/tools/{toolId}
Authorization: Bearer {{userToken}}
```

响应中包含 `fieldSchemas` 字段，前端据此渲染动态表单。

---

### 2.6 任务（用户端）

#### 2.6.1 创建任务

```
POST /api/v1/tasks
Authorization: Bearer {{userToken}}
Content-Type: application/json

{
  "toolCode": "xiaohongshu_copywriting",
  "params": {
    "productName": "五一护理套餐",
    "targetCustomer": "年轻女性",
    "style": "种草"
  }
}
```

**响应：**
```json
{
  "code": "SUCCESS",
  "data": {
    "taskId": 90001,
    "taskNo": "T202605090001",
    "status": "QUEUED"
  }
}
```

#### 2.6.2 获取任务列表

```
GET /api/v1/tasks
Authorization: Bearer {{userToken}}
```

#### 2.6.3 获取任务详情

```
GET /api/v1/tasks/{taskId}
Authorization: Bearer {{userToken}}
```

#### 2.6.4 获取任务状态

```
GET /api/v1/tasks/{taskId}/status
Authorization: Bearer {{userToken}}
```

**测试用例：**

| 用例 | 操作 | 期望结果 |
|------|------|---------|
| TASK-001 | 用户创建任务 | 返回 taskId、taskNo |
| TASK-002 | 创建任务后查状态 | 状态为 QUEUED |
| TASK-003 | 算力为 0 的用户创建任务 | 返回算力不足错误 |
| TASK-004 | 10 秒内重复提交相同参数 | 返回已有 taskId（幂等）或仅创建一次 |
| TASK-005 | 用户 A 查看用户 B 的任务 | 返回 403 或查不到 |
| TASK-006 | 工具下架后创建任务 | 返回错误 |

---

### 2.7 任务管理（管理员）

#### 2.7.1 获取所有任务

```
GET /api/admin/v1/tasks?status=QUEUED&toolCode=xxx&userId=1
Authorization: Bearer {{adminToken}}
```

#### 2.7.2 获取任务详情

```
GET /api/admin/v1/tasks/{taskId}
Authorization: Bearer {{adminToken}}
```

#### 2.7.3 重试任务

```
POST /api/admin/v1/tasks/{taskId}/retry
Authorization: Bearer {{adminToken}}
```

#### 2.7.4 取消任务

```
POST /api/admin/v1/tasks/{taskId}/cancel
Authorization: Bearer {{adminToken}}
```

---

### 2.8 算力

#### 2.8.1 查询算力账户

```
GET /api/v1/credits/account
Authorization: Bearer {{userToken}}
```

#### 2.8.2 管理员手动加算力

```
POST /api/admin/v1/users/{userId}/credits/manual-add
Authorization: Bearer {{adminToken}}
Content-Type: application/json

{
  "amount": 50,
  "reason": "测试加算力"
}
```

**测试用例：**

| 用例 | 操作 | 期望结果 |
|------|------|---------|
| CREDIT-001 | 创建任务后查算力 | 余额减少（冻结算力） |
| CREDIT-002 | 任务成功后查算力 | 冻结部分已扣除 |
| CREDIT-003 | 任务失败后查算力 | 冻结部分已释放 |
| CREDIT-004 | 管理员手动加算力 | 用户算力余额增加 |
| CREDIT-005 | 查看算力流水 | 流水记录与余额变动一致 |

---

### 2.9 用户管理（管理员）

#### 2.9.1 获取用户列表

```
GET /api/admin/v1/users
Authorization: Bearer {{adminToken}}
```

响应包含用户的算力账户信息。

---

### 2.10 Worker 内部接口

> Header 需要带 Internal-Auth 或 Token（Worker 专用）

#### 2.10.1 获取执行上下文

```
GET /api/internal/v1/tasks/{taskId}/execution-context
```

**响应：**
```json
{
  "code": "SUCCESS",
  "data": {
    "taskId": 90001,
    "taskNo": "T202605090001",
    "toolCode": "xiaohongshu_copywriting",
    "status": "QUEUED",
    "params": {
      "productName": "五一护理套餐",
      "targetCustomer": "年轻女性",
      "style": "种草"
    },
    "systemPrompt": "你是一个专业小红书文案助手。",
    "userPromptTemplate": "请根据 {{productName}} 为 {{targetCustomer}} 生成一篇 {{style}} 风格文案。",
    "outputFormat": "MARKDOWN",
    "modelProviderCode": "deepseek",
    "modelName": "deepseek-chat"
  }
}
```

#### 2.10.2 标记处理中

```
POST /api/internal/v1/tasks/{taskId}/processing
Content-Type: application/json

{
  "workerId": "worker-001"
}
```

#### 2.10.3 标记成功

```
POST /api/internal/v1/tasks/{taskId}/success
Content-Type: application/json

{
  "resourceType": "MARKDOWN",
  "contentText": "五一出游季，来一场说走就走的护理之旅吧！...",
  "contentJson": null,
  "modelProviderCode": "deepseek",
  "modelName": "deepseek-chat"
}
```

#### 2.10.4 标记失败

```
POST /api/internal/v1/tasks/{taskId}/failed
Content-Type: application/json

{
  "errorCode": "MODEL_CALL_FAILED",
  "errorMessage": "模型调用超时"
}
```

**Worker 错误码：**

| 错误码 | 场景 |
|--------|------|
| `PROMPT_VARIABLE_MISSING` | Prompt 模板变量缺失 |
| `MODEL_CALL_FAILED` | 模型调用失败 |
| `MODEL_TIMEOUT` | 模型超时 |
| `MODEL_OUTPUT_EMPTY` | 模型返回空内容 |
| `WORKER_INTERNAL_ERROR` | Worker 内部异常 |

**测试用例：**

| 用例 | 操作 | 期望结果 |
|------|------|---------|
| WORKER-001 | Worker 从 Redis 取消息 | 能取到 QUEUED 任务 |
| WORKER-002 | Worker 调 processing 接口 | 任务状态变为 PROCESSING |
| WORKER-003 | 模型成功，调 success 接口 | 任务状态变为 SUCCESS，算力扣除 |
| WORKER-004 | 模型失败，调 failed 接口 | 任务状态变为 FAILED，算力释放 |
| WORKER-005 | 重复调 success 接口 | 不重复扣费（幂等） |

---

## 3. 用户前端测试

### 3.1 页面路由

| 路由 | 页面 | 说明 |
|------|------|------|
| `/login` | 登录页 | 用户登录 |
| `/` → `/tools` | 工具列表 | 查看已发布工具 |
| `/tools/:toolId` | 工具详情 | 查看工具 + 动态表单 |
| `/tasks` | 任务列表 | 查看我的任务 |
| `/tasks/:taskId` | 任务详情 | 查看结果 |

### 3.2 测试要点

| 测试项 | 操作 | 预期 |
|--------|------|------|
| 未登录跳转 | 直接访问 `/tools` | 自动跳转到登录页 |
| 登录 | 输入 user1/123456 | 登录成功，跳转工具列表 |
| 密码错误 | 输入 user1/wrong | 提示密码错误 |
| 禁用用户 | disabled_user 登录 | 提示账号被禁用 |
| 工具列表 | 管理员发布了工具 | 能看到已发布工具 |
| 工具详情 | 点击工具 | 展示动态表单（文本框、下拉框等） |
| 必填校验 | 必填字段留空提交 | 前端拦截，提示必填 |
| 创建任务 | 填写表单并提交 | 显示提交中，返回 taskId |
| 防重复提交 | 10 秒内重复点击 | 只创建一次或返回已有任务 |
| 任务列表 | 查看任务 | 显示任务状态（QUEUED/PROCESSING/SUCCESS/FAILED） |
| 任务结果 | 点击 SUCCESS 任务 | 展示 AI 生成的文案内容 |
| 算力显示 | 查看余额 | 显示当前算力余额 |

---

## 4. 管理后台测试

### 4.1 页面路由

| 路由 | 页面 | 说明 |
|------|------|------|
| `/login` | 登录页 | 管理员登录 |
| `/tools` | 工具列表 | 管理所有工具 |
| `/tools/new` | 创建工具 | 创建新工具 |
| `/tools/:toolId/edit` | 编辑工具 | 编辑工具信息 |
| `/tools/:toolId/fields` | 字段 Schema | 配置动态表单字段 |
| `/tools/:toolId/prompts` | Prompt 配置 | 配置 AI Prompt |
| `/tasks` | 任务列表 | 查看所有用户的任务 |
| `/tasks/:taskId` | 任务详情 | 查看任务详情 + 失败原因 |
| `/users` | 用户管理 | 查看用户和算力管理 |

### 4.2 测试要点

| 测试项 | 操作 | 预期 |
|--------|------|------|
| 普通用户访问 | user1 token 访问管理页 | 跳转登录或 403 |
| 创建工具 | 填写信息创建 | 草稿状态 |
| 字段配置 | 添加 TEXT / SELECT 字段，发布 schema | 保存成功 |
| SELECT 选项 | 配置选项 label/value | 前端展示正确 |
| Prompt 配置 | 编写 prompt 模板，发布版本 | 保存成功 |
| Prompt 变量 | 使用 `{{varName}}` 语法 | 保存成功 |
| 发布工具 | 点击发布（需有字段+prompt） | 发布成功 |
| 工具列表 | 查看 | 显示状态标签（草稿/已发布/已下架） |
| 任务列表 | 查看 | 显示所有用户任务，可按状态筛选 |
| 失败原因 | 查看 FAILED 任务详情 | 显示 errorCode 和 errorMessage |
| 重试任务 | 点击重试 | 任务重新入队 |
| 取消任务 | 点击取消 | 任务状态变为 CANCELLED |
| 用户管理 | 查看用户列表 | 显示用户名、算力余额 |
| 手动加算力 | 输入金额 + 原因确认 | 用户算力增加 |

---

## 5. Worker 测试

### 5.1 启动方式

```bash
cd worker
pip install -r requirements.txt
python main.py
```

### 5.2 依赖环境

| 依赖 | 说明 |
|------|------|
| Redis | `localhost:6379`，队列名 `ai:task:queue` |
| 后端 API | `localhost:8080` |
| AI 模型 Key | 在配置文件中设置（如 DeepSeek API Key） |

### 5.3 Redis 消息格式

Worker 从队列 `ai:task:queue` 消费的消息体：

```json
{
  "taskId": 90001,
  "taskNo": "T202605070001",
  "toolCode": "xiaohongshu_copywriting",
  "traceId": "request-id",
  "createdAt": "2026-05-07T10:00:00"
}
```

### 5.4 执行流程

```
1. 从 Redis BLPop ai:task:queue 获取消息
2. GET /api/internal/v1/tasks/{taskId}/execution-context  → 获取上下文
3. POST /api/internal/v1/tasks/{taskId}/processing        → 标记处理中
4. 替换 prompt 模板变量 {{var}} → params 中对应值
5. 调用 AI 模型（DeepSeek 等）
6. 成功 → POST /api/internal/v1/tasks/{taskId}/success
7. 失败 → POST /api/internal/v1/tasks/{taskId}/failed
```

### 5.5 Prompt 变量替换规则

- `{{productName}}` → `params.productName`
- `{{targetCustomer}}` → `params.targetCustomer`
- `{{style}}` → `params.style`
- 变量缺失 → 回写 FAILED，errorCode = `PROMPT_VARIABLE_MISSING`

### 5.6 测试要点

| 测试项 | 操作 | 预期 |
|--------|------|------|
| 启动 | 运行 python main.py | Worker 启动，连接到 Redis |
| 消费任务 | 管理员创建一个工具 → 用户创建任务 | Worker 消费到消息 |
| PROCESSING | Worker 调 processing 接口 | 后端任务状态变更 |
| 变量替换 | Prompt 中含 `{{productName}}` | 正确替换为用户输入值 |
| AI 调用 | Worker 调 AI 模型 | 返回生成结果 |
| 成功回写 | AI 成功 → 调 success 接口 | 任务 SUCCESS，内容可查 |
| 失败回写 | AI 失败 → 调 failed 接口 | 任务 FAILED，可看原因 |
| 幂等 | 重复调 success | 不重复扣算力 |
| 日志 | Worker 运行中 | 打印关键步骤日志 |

---

## 6. 全链路测试场景

### 6.1 核心链路（P0）

```
管理员登录 → 创建工具 → 配置字段 Schema → 配置 Prompt → 发布工具
  → 用户登录 → 看到工具 → 填写表单 → 创建任务
  → Worker 消费 → AI 生成 → 回写结果
  → 用户查看结果 → 管理员后台查看任务状态
```

### 6.2 验证步骤

| 步骤 | 操作 | 验证点 |
|------|------|--------|
| 1 | 启动基础设施 | `docker ps` 确认 MySQL + Redis 运行 |
| 2 | 启动后端 | `curl http://localhost:8080/api/health` 返回 UP |
| 3 | 管理员创建工具 | 后台工具列表出现新工具 |
| 4 | 配置字段 Schema | 工具详情页能看到字段配置 |
| 5 | 配置 Prompt | 配置正确 |
| 6 | 发布工具 | 状态变为"已发布" |
| 7 | 用户前端查看 | 工具出现在列表 |
| 8 | 用户创建任务 | 返回 taskId，状态 QUEUED |
| 9 | 检查 Redis | `redis-cli LLEN ai:task:queue` > 0 |
| 10 | 启动 Worker | Worker 开始消费 |
| 11 | 查看任务状态 | PROCESSING → SUCCESS |
| 12 | 用户查看结果 | 能看到 AI 生成内容 |
| 13 | 管理员查看 | 任务详情页显示完整信息 |
| 14 | 检查算力 | 用户算力正确扣除 |

### 6.3 异常链路

| 场景 | 触发方式 | 预期结果 |
|------|---------|---------|
| 算力不足 | 新建用户或消耗完算力后创建任务 | 创建失败，提示算力不足 |
| 工具下架后操作 | 下架工具后，用户前端刷新 | 工具不再展示 |
| 模型失败 | 配置错误的 API Key | 任务变为 FAILED，显示错误原因 |
| 未登录访问 | 清除 token 后访问接口 | 返回 401 |
| 密码错误 | 输入错误密码 | 登录失败 |

---

## 7. Apifox 配置建议

### 7.1 环境变量

| 变量名 | 初始值 | 说明 |
|--------|--------|------|
| `baseUrl` | `http://localhost:8080` | 后端地址 |
| `userToken` | — | 用户登录后提取 |
| `adminToken` | — | 管理员登录后提取 |
| `toolId` | — | 创建工具后提取 |
| `taskId` | — | 创建任务后提取 |

### 7.2 目录结构（建议）

```
AI Tool Market
├── 01-健康检查
├── 02-认证
│   ├── 用户登录
│   ├── 管理员登录
│   └── 获取当前用户
├── 03-工具管理（管理员）
│   ├── 创建/编辑/发布/下架
│   ├── 字段 Schema 管理
│   └── Prompt 管理
├── 04-工具（用户端）
│   ├── 分类列表
│   ├── 工具列表
│   └── 工具详情
├── 05-任务（用户端）
│   ├── 创建任务
│   ├── 任务列表
│   ├── 任务详情
│   └── 任务状态
├── 06-任务管理（管理员）
│   ├── 任务列表
│   ├── 任务详情
│   ├── 重试
│   └── 取消
├── 07-算力
│   ├── 查询账户
│   └── 手动加算力（管理员）
├── 08-用户管理（管理员）
│   └── 用户列表
├── 09-Worker 内部接口
│   ├── 获取执行上下文
│   ├── 标记处理中
│   ├── 标记成功
│   └── 标记失败
└── 10-全链路测试
    ├── 正常链路（管理员→工具→用户→任务→Worker→结果）
    └── 异常链路（算力不足/模型失败/未登录）
```

### 7.3 自动化测试流程

建议按以下顺序编排 Apifox 测试用例集（依赖前序接口的响应值）：

1. **健康检查** → `GET /api/health`
2. **管理员登录** → `POST /api/admin/v1/auth/login` → 提取 adminToken
3. **创建工具** → `POST /api/admin/v1/tools` → 提取 toolId
4. **创建字段 Schema** → `POST /api/admin/v1/tools/{toolId}/field-schemas`
5. **发布字段 Schema** → `POST /api/admin/v1/field-schemas/{schemaId}/publish`
6. **创建 Prompt** → `POST /api/admin/v1/tools/{toolId}/prompts` → 提取 promptId
7. **创建 Prompt 版本** → `POST /api/admin/v1/prompts/{promptId}/versions` → 提取 versionId
8. **发布 Prompt 版本** → `POST /api/admin/v1/prompt-versions/{versionId}/publish`
9. **发布工具** → `POST /api/admin/v1/tools/{toolId}/publish`
10. **用户登录** → `POST /api/v1/auth/login` → 提取 userToken
11. **查看工具列表** → `GET /api/v1/tools` → 确认工具可见
12. **创建任务** → `POST /api/v1/tasks` → 提取 taskId
13. **查询任务状态** → `GET /api/v1/tasks/{taskId}/status` → 验证 QUEUED
14. **Worker 获取上下文** → `GET /api/internal/v1/tasks/{taskId}/execution-context`
15. **Worker 标记处理中** → `POST /api/internal/v1/tasks/{taskId}/processing`
16. **Worker 标记成功** → `POST /api/internal/v1/tasks/{taskId}/success`
17. **查看任务结果** → `GET /api/v1/tasks/{taskId}` → 验证 SUCCESS + 内容
