<<<<<<< HEAD

# 05. 成员 5：测试 / 文档 / 部署

## 角色定位

你负责保证项目最后能交付，而不是只做记录。每天都要跑主链路，及时发现接口、数据库、前端、Worker 的不一致。

```text
接口测试集合
测试账号和测试数据
每日联调问题表
Bug 列表
部署说明
最终验收报告
演示脚本
```

## 测试数据

# <<<<<<< HEAD

至少准备：


| 模块     | 技术                              |
| ------ | ------------------------------- |
| 后端     | Spring Boot 3.x, Java 17, Maven |
| 用户端    | Vue 3, Vite, Axios, Pinia       |
| 管理后台   | Vue 3, Vite, Element Plus       |
| Worker | Python 3                        |
| 数据库    | MySQL 8                         |
| 队列/缓存  | Redis                           |
| 本地环境   | Docker Compose                  |


## 推荐目录结构

> > > > > > > origin/feature/admin-web

```text
普通用户：user1 / 123456
管理员：admin / 123456
禁用用户：disabled_user / 123456
工具分类：文案生成
测试工具：小红书文案生成
默认算力：100
工具消耗：10
```

## 每日测试重点


| 日期    | 测试重点                         |
| ----- | ---------------------------- |
| 第 1 天 | 前端、后端、Worker、Redis、MySQL 能启动 |
| 第 2 天 | 登录、后台创建工具、用户端看到工具            |
| 第 3 天 | 创建任务、冻结算力、任务进入 QUEUED        |
| 第 4 天 | Worker 消费、AI 生成、结果保存         |
| 第 5 天 | 后台查看任务和失败原因、手动加算力            |
| 第 6 天 | 异常流程和全链路回归                   |
| 第 7 天 | 部署验收、演示脚本、最终报告               |


## P0 测试用例

### 登录

```text
AUTH-001 用户正确账号密码登录成功
AUTH-002 密码错误登录失败
AUTH-003 禁用用户不能登录
AUTH-004 未登录访问用户接口返回 401
AUTH-005 普通用户访问后台返回 403
AUTH-006 管理员登录后台成功
```

### 工具

```text
TOOL-001 管理员创建工具草稿成功
TOOL-002 没有字段 Schema 不能发布
TOOL-003 没有 Prompt 不能发布
TOOL-004 发布后用户端能看到工具
TOOL-005 下架后用户端看不到工具
```

### 动态表单

```text
FORM-001 工具详情返回字段配置
FORM-002 必填字段为空前端拦截
FORM-003 绕过前端提交空字段，后端拒绝
FORM-004 select 字段选项展示正确
```

### 任务

```text
TASK-001 用户创建任务成功，返回 taskId
TASK-002 创建任务后状态为 QUEUED
TASK-003 算力不足不能创建任务
TASK-004 10 秒内重复点击只创建一个任务或返回已有任务
TASK-005 用户不能查看别人的任务
TASK-006 工具下架后不能创建任务
```

### Worker

```text
WORKER-001 Worker 能消费 Redis 任务
WORKER-002 Worker 开始执行后任务变为 PROCESSING
WORKER-003 模型成功后任务变为 SUCCESS
WORKER-004 模型失败后任务变为 FAILED
WORKER-005 重复 success 回写不重复扣费
```

<<<<<<< HEAD

### 算力

```text
CREDIT-001 创建任务冻结算力
CREDIT-002 任务成功扣除算力
CREDIT-003 任务失败释放算力
CREDIT-004 管理员手动加算力成功
CREDIT-005 算力流水和账户余额能对上
```

## 每日联调问题表格式

```text
日期：
发现人：
模块：
问题描述：
复现步骤：
期望结果：
实际结果：
责任人：
优先级：P0/P1/P2
状态：待处理/处理中/已修复/已验证
```

## 最终验收报告模板

```text
AI 超市平台 V1 7 天交付验收报告

一、验收时间：
二、验收环境：
三、参与人员：

四、核心链路：
1. 用户能登录：通过/不通过
2. 管理员能登录：通过/不通过
3. 管理员能创建并发布工具：通过/不通过
4. 用户能看到工具：通过/不通过
5. 用户能提交任务：通过/不通过
6. Worker 能完成 AI 生成：通过/不通过
7. 用户能看到结果：通过/不通过
8. 后台能看到任务状态和失败原因：通过/不通过

五、遗留问题：
六、上线/演示结论：允许交付/暂缓交付
```

## 部署检查清单

```text
MySQL 表结构已执行
初始化数据已导入
Redis 连接正常
后端服务启动正常
Worker 启动正常
前端构建成功
后台构建成功
模型 Key 已配置
管理员账号可登录
普通用户账号可登录
核心链路测试通过
```

## 必须交付

=======

> > > > > > > # origin/feature/admin-web

# ai-tool-market

## V1 工具数量建议

V1.0 先上线 6 个核心工具：


| 分类    | 工具          |
| ----- | ----------- |
| 内容创作  | AI 朋友圈文案生成器 |
| 内容创作  | AI 小红书文案生成器 |
| 短视频运营 | AI 短视频脚本生成器 |
| 短视频运营 | AI 短视频选题生成器 |
| 电商运营  | AI 商品标题优化器  |
| 门店获客  | AI 门店活动策划器  |


# AI 任务 Worker

## 角色定位

你负责 Redis 队列消费、Prompt 拼装、AI 模型调用、任务结果回写。不要直接绕过后端改核心业务表，算力和任务状态由后端统一处理。

## 必须交付

```
Redis 队列消费者
任务执行上下文获取
Prompt 模板变量替换
AI 模型调用
成功结果回写
失败状态回写
Worker 启动说明

```

## Redis 队列

队列名：

```
ai:task:queue

```

消息格式：

```
{
  "taskId": 90001,
  "taskNo": "T202605070001",
  "toolCode": "xiaohongshu_copywriting",
  "traceId": "request-id",
  "createdAt": "2026-05-07T10:00:00"
}

```

## Worker 执行流程

```
1. 从 Redis 获取消息
2. 调用 GET /api/internal/v1/tasks/{taskId}/execution-context
3. 调用 POST /api/internal/v1/tasks/{taskId}/processing
4. 根据 field inputs + prompt template 拼装 Prompt
5. 调用 AI 模型
6. 成功则调用 POST /api/internal/v1/tasks/{taskId}/success
7. 失败则调用 POST /api/internal/v1/tasks/{taskId}/failed

```

## 执行上下文返回结构

后端返回：

```
{
  "taskId": 90001,
  "taskNo": "T202605070001",
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

```

## Prompt 变量替换规则

```
{{productName}} 替换为 params.productName
{{targetCustomer}} 替换为 params.targetCustomer
{{style}} 替换为 params.style
缺少变量时，回写 FAILED，errorCode = PROMPT_VARIABLE_MISSING

```

## 成功回写

```
POST /api/internal/v1/tasks/{taskId}/success

```

```
{
  "resourceType": "MARKDOWN",
  "contentText": "生成结果",
  "contentJson": null,
  "modelProviderCode": "deepseek",
  "modelName": "deepseek-chat"
}

```

## 失败回写

```
POST /api/internal/v1/tasks/{taskId}/failed

```

```
{
  "errorCode": "MODEL_CALL_FAILED",
  "errorMessage": "模型调用失败"
}

```

## V1 错误码

错误码

场景

`PROMPT_VARIABLE_MISSING`

Prompt 变量缺失

`MODEL_CALL_FAILED`

模型调用失败

`MODEL_TIMEOUT`

模型超时

`MODEL_OUTPUT_EMPTY`

模型返回空内容

`WORKER_INTERNAL_ERROR`

Worker 内部异常

## 每日交付

日期

交付

第 1 天

Worker 项目启动、Redis 连接、消费空消息

第 2 天

execution-context 接口联调

第 3 天

任务状态 PROCESSING 回写

第 4 天

AI 调用和 SUCCESS/FAILED 回写

第 5 天

异常处理、超时处理、日志

第 6 天

联调修 Bug

第 7 天

Worker 启动文档和演示环境验证

## 不做

```
多模型路由
文件上传
图片/视频生成
直接修改 credit_accounts
直接修改 ai_tasks 成功状态
复杂死信队列后台

```

