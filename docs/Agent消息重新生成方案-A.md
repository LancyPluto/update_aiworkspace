# Agent 消息重新生成开发方案（方案 A：复用 USER）

| 项 | 内容 |
| --- | --- |
| 状态 | **已落地**（后端：`backend`；契约：`docs/api/openapi.yml`；测试库：`schema-test.sql`） |
| 负责人 | 后端（`backend`）为主；前端联调；`agent-service` 无新用户接口 |
| 方案 | **A — 复用 USER 记录**：编辑时 `UPDATE` 同一条 USER；重新生成不新建 USER |
| 更新时间 | 2026-05-20 |

---

## 1. 背景与目标

### 1.1 现状

- 用户发消息：`POST /api/v1/agent/sessions/{sessionId}/messages` → 插入 USER + `agent_run` → `agent-service` 执行 → 完成后插入 ASSISTANT。
- **不支持**：对已有回复「重新生成」；**不支持**：修改用户输入后重新生成。
- `agent-service` 通过内部接口 `GET /api/internal/v1/agent/runs/{runId}/context` 拉历史；历史为会话最近 20 条，**不区分分支**，无法支撑「去掉旧回复再答」。

### 1.2 目标（V1）

| 能力 | 说明 |
| --- | --- |
| 重新生成 | 对某轮已完成（或失败/取消）的回复，**不改 USER 原文**，新建 `agent_run` 再执行，旧 ASSISTANT 不再展示 |
| 编辑后重新生成 | 用户修改某条 **USER** 消息内容后，新建 `agent_run`；该 USER **之后**的所有消息（含旧 ASSISTANT 及更晚轮次）不再展示 |
| 方案 A 约束 | 每个「用户提问」在 DB 中**始终对应一条** `role=USER` 的 `agent_messages` 记录（`messageId` 不变） |

### 1.3 非目标（V1 不做）

- 保留用户每次编辑的原文历史（若需要，后续升级为方案 B 或增加 `content_history` 表）。
- 同一 USER 下并行展示多个 ASSISTANT 版本供用户切换。
- 在 `agent-service` 暴露面向用户的 regenerate API。
- 对 `RUNNING` 中的 run 静默覆盖（须拒绝或先 cancel）。

---

## 2. 方案 A 数据语义

### 2.1 核心原则

1. **USER 一行 = 用户的一问**（`id` 生命周期内不变）。
2. **ASSISTANT 可多行**，每行绑定一个 `run_id`；仅 `status=ACTIVE` 且对应当前「有效 run」的一条在列表展示。
3. **编辑**：`UPDATE agent_messages SET content_text=... WHERE id=? AND role='USER'`。
4. **截断**：将 `id > anchorUserMessageId` 的消息标为 `SUPERSEDED`（含旧 ASSISTANT 与更晚的 USER/ASSISTANT）。
5. **重新生成（不改字）**：不更新 USER；仅 supersede **该轮及之后**的 ASSISTANT/后续轮次（见 §4.2 锚点规则）。

### 2.2 示例（编辑后重新生成）

```text
# 初始
id=100 USER      "帮我写文案"           ACTIVE   run_id=1
id=101 ASSISTANT "回答 v1"              ACTIVE   run_id=1

# 用户编辑 id=100 为 "帮我写小红书文案" 并重新生成
id=100 USER      "帮我写小红书文案"     ACTIVE   run_id=2   ← 同 id，content 更新，链到新 run
id=101 ASSISTANT "回答 v1"              SUPERSEDED run_id=1
id=102 ASSISTANT "回答 v2"              ACTIVE   run_id=2

# 用户对 run_id=2 点「重新生成」（不改字）
id=100 USER      "帮我写小红书文案"     ACTIVE   run_id=3
id=102 ASSISTANT "回答 v2"              SUPERSEDED run_id=2
id=103 ASSISTANT "回答 v3"              ACTIVE   run_id=3
```

前端列表只拉 `status=ACTIVE` 消息 → 始终为 1 条 USER + 1 条 ASSISTANT（按 `id` 排序）。

---

## 3. 数据库变更

### 3.1 `agent_messages`

```sql
ALTER TABLE agent_messages
  ADD COLUMN status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE|SUPERSEDED',
  ADD COLUMN superseded_at DATETIME NULL COMMENT '作废时间';

CREATE INDEX idx_agent_messages_session_active
  ON agent_messages (session_id, status, id);
```

| 字段 | 说明 |
| --- | --- |
| `status` | `ACTIVE`：参与列表与上下文；`SUPERSEDED`：仅审计，不参与 |
| `superseded_at` | 作废时间，便于排查 |

**存量数据**：迁移脚本将现有行全部设为 `ACTIVE`。

### 3.2 `agent_runs`（推荐，便于排查）

```sql
ALTER TABLE agent_runs
  ADD COLUMN parent_run_id BIGINT NULL COMMENT '重新生成来源 run',
  ADD COLUMN source_user_message_id BIGINT NULL COMMENT '本轮对应的 USER message id',
  ADD COLUMN client_request_id VARCHAR(64) NULL COMMENT '客户端幂等键';

CREATE UNIQUE INDEX uk_agent_runs_user_client
  ON agent_runs (user_id, client_request_id);
```

说明：`client_request_id` 为空时不参与唯一约束（MySQL 中 NULL 可重复；实现时幂等仅对非空 key 生效）。

### 3.3 同步清单

按仓库规则同步：

| 路径 | 动作 |
| --- | --- |
| `sql/005_agent_module.sql` 或新文件 `sql/00x_agent_regenerate.sql` | 增量 DDL |
| `backend/src/test/resources/schema-test.sql` | 测试库表结构 |
| `DataInitializer`（若有 agent 表初始化） | 对齐字段 |
| `agent_messages` / `agent_runs` Entity | 新字段 |

---

## 4. API 设计

### 4.1 重新生成

```http
POST /api/v1/agent/runs/{runId}/regenerate
Authorization: Bearer <token>
Content-Type: application/json

{
  "clientRequestId": "optional-uuid-max-64"
}
```

**响应**（与发消息一致，`messageId` 为**原 USER id**）：

```json
{
  "code": 0,
  "data": {
    "sessionId": 1,
    "messageId": 100,
    "runId": 3,
    "runStatus": "RUNNING"
  }
}
```

| 校验 | 错误码建议 |
| --- | --- |
| run 不存在或非本人 | `AGENT_RUN_NOT_FOUND` |
| run 非终态（`CREATED`/`RUNNING`/`WAITING_USER_CONFIRMATION`） | `AGENT_RUN_NOT_REGENERATABLE` |
| 同用户已有进行中的 run（可选：同 session） | `AGENT_ACTIVE_RUN_EXISTS` |
| 算力不足 / 限流 | 沿用 `sendMessage` 现有码 |

### 4.2 编辑后重新生成

```http
POST /api/v1/agent/sessions/{sessionId}/messages/{messageId}/edit-regenerate
Authorization: Bearer <token>
Content-Type: application/json

{
  "content": "新内容，必填，max 8000",
  "clientRequestId": "optional-uuid"
}
```

**响应**：同上；`messageId` **仍为** path 中的 `{messageId}`（方案 A）。

| 校验 | 错误码建议 |
| --- | --- |
| message 非 USER | `AGENT_MESSAGE_NOT_EDITABLE` |
| message 不属于 session / 用户 | `AGENT_MESSAGE_NOT_FOUND` |
| `content` 与当前相同且仅想重答 | 应走 `regenerate` 接口，可返回 400 提示 |

### 4.3 锚点与 supersede 规则

设 **锚点** `anchorUserMessageId`：

| 接口 | 锚点 | supersede 范围 |
| --- | --- | --- |
| `regenerate(runId)` | 该 run 的 USER message id | `session_id` 相同且 `id > anchorUserMessageId` 的所有消息 |
| `edit-regenerate(sessionId, messageId)` | path 中的 USER `messageId` | 同上；且 **先** `UPDATE` 锚点 USER 的 `content_text` |

**不** supersede 锚点 USER 本身。

**重新生成是否 supersede 更晚的 USER 轮次？**  
要。否则 `context()` 仍会带上后续轮次。V1 统一：`id > anchor` 全部 `SUPERSEDED`。

### 4.4 幂等

- 请求带 `clientRequestId` 时：查 `agent_runs` 是否已有 `(user_id, client_request_id)`。
- 若存在且为本次操作创建的 run：直接返回该 run 的 `CreateAgentMessageResponse`（`runStatus` 取当前库中状态）。
- `sendMessage` 建议 V1 一并接入同一机制（DTO 已有字段未使用）。

### 4.5 OpenAPI

在 `docs/api/openapi.yml` 增加上述路径、`RegenerateAgentRunRequest`、`EditRegenerateAgentMessageRequest`，响应复用 `CreateAgentMessageResponse`。

---

## 5. 服务层设计（`backend`）

### 5.1 类与文件

| 类型 | 路径 |
| --- | --- |
| Controller | `AgentRunController` 增加 `regenerate`；`AgentSessionController` 增加 `editRegenerate` |
| Service | `AgentRunService` / `AgentRunServiceImpl` |
| DTO | `RegenerateAgentRunRequest`、`EditRegenerateAgentMessageRequest` |
| Mapper | `AgentMessageMapper`、`AgentRunMapper` 新增方法 |
| 错误码 | `ErrorCode` 枚举 |

### 5.2 抽取 `startRun`（与 `sendMessage` 共用）

将 `AgentRunServiceImpl.sendMessage` 中「创建 run → 模型预检 → 冻结算力 → `run.started` → 事务后 `executeRun`」抽取为：

```java
/**
 * @param existingUserMessage 已存在的 USER（send/regenerate/edit 后共用）
 * @param parentRunId         regenerate/edit-regenerate 时填来源 runId，首发为 null
 */
private CreateAgentMessageResponse startRun(
    Long userId,
    AgentSession session,
    AgentMessage existingUserMessage,
    Long parentRunId,
    String clientRequestId
)
```

流程要点：

1. 校验 session `ACTIVE`、限流、算力、`checkActiveRunLimit`。
2. 幂等：有 `clientRequestId` 则先查已存在 run。
3. `insertRun`，设置 `source_user_message_id = existingUserMessage.getId()`，`parent_run_id = parentRunId`。
4. `existingUserMessage.setRunId(newRunId)` + `updateById`（**方案 A：USER 行指向最新 run**）。
5. 模型预检、`freeze`、`markRunning`、`appendEvent(run.started)`。
6. `runAfterCommit(() -> agentServiceClient.executeRun(newRunId))`。
7. 返回 `CreateAgentMessageResponse(sessionId, existingUserMessage.getId(), newRunId, "RUNNING")`。

### 5.3 `regenerate(userId, runId, request)`

```text
1. run = findRun(runId, userId)
2. assert run.status in TERMINAL_STATUSES
3. userMsg = findUserMessageByRunId(runId) — 不存在则 404
4. supersedeMessagesAfter(sessionId, userMsg.getId(), now)
5. return startRun(userId, session, userMsg, parentRunId=runId, clientRequestId)
```

**不**修改 `userMsg.contentText`。

### 5.4 `editRegenerate(userId, sessionId, messageId, request)`

```text
1. userMsg = findMessage(messageId, userId, sessionId), role must be USER
2. if session has cancellable run for this user → 409 或要求先 cancel（与产品确认，建议拒绝）
3. trim content; 可选：与当前相同则仅 supersede+startRun 等价 regenerate
4. UPDATE userMsg.content_text
5. supersedeMessagesAfter(sessionId, messageId, now)
6. return startRun(..., userMsg, parentRunId=null 或最近 runId, clientRequestId)
```

### 5.5 `completeRun` 调整

成功完成时插入 ASSISTANT 前：

- 将**同一 `run_id` 下已有 ACTIVE 的 ASSISTANT**（若存在）标为 `SUPERSEDED`（防止 regenerate 竞态重复 ACTIVE）。
- 新 ASSISTANT：`status=ACTIVE`，`run_id=当前 run`。

### 5.6 `context(runId)` — 关键

**现状问题**：`findLatestBySession(sessionId, 20)` 含已作废回复与后续轮次。

**改为**：

```java
Long anchorId = run.getSourceUserMessageId();
if (anchorId == null) {
    AgentMessage userMsg = agentMessageMapper.findUserMessageByRunId(runId);
    anchorId = userMsg != null ? userMsg.getId() : null;
}
List<InternalAgentMessageResponse> history =
    agentMessageMapper.findActiveHistoryBefore(sessionId, anchorId, HISTORY_LIMIT);
// ORDER BY id ASC，映射 role + contentText
```

Mapper SQL 示意：

```sql
SELECT * FROM agent_messages
WHERE session_id = #{sessionId}
  AND status = 'ACTIVE'
  AND (#{beforeMessageId} IS NULL OR id < #{beforeMessageId})
ORDER BY id DESC
LIMIT #{limit}
```

Java 侧再按 `id ASC` 排序。当前轮用户输入仍用 `findUserMessageByRunId(runId).contentText` 填入 `InternalAgentRunContextResponse.message`。

### 5.7 会话消息列表

`AgentSessionService.messages` / `AgentMessageMapper.findBySession` 增加条件：

```sql
AND m.status = 'ACTIVE'
```

保证前端刷新后只见当前分支。

### 5.8 `supersedeMessagesAfter`

```sql
UPDATE agent_messages
SET status = 'SUPERSEDED', superseded_at = #{now}
WHERE session_id = #{sessionId}
  AND id > #{anchorUserMessageId}
  AND status = 'ACTIVE'
```

---

## 6. `agent-service` 边界

| 项 | 结论 |
| --- | --- |
| 新接口 | **不需要** |
| 执行路径 | 仍为 `POST /internal/v1/agent/runs/{runId}/execute` |
| 依赖变更 | `GET context` 返回的 `history` 已截断且不含 SUPERSEDED 即可 |
| 验证 | 现有 `e2e_agent_smoke` / 单测在 backend 改完后跑一轮 regenerate 场景 |

---

## 7. 前端联调约定（供参考）

| 操作 | 调用 | UI 行为 |
| --- | --- | --- |
| 重新生成 | `POST /agent/runs/{runId}/regenerate` | 用返回的 `runId` 轮询或 SSE `GET /runs/{runId}/events`；**不**新增 USER 气泡 |
| 编辑发送 | `POST .../messages/{messageId}/edit-regenerate` | 更新本地该 USER 气泡文案；用新 `runId` 追 ASSISTANT 流式内容 |
| 列表 | `GET .../sessions/{id}/messages` | 仅 ACTIVE；旧 ASSISTANT 自动消失 |

`CreateAgentMessageResponse.messageId` 在方案 A 下**不变**，前端可继续用原 `messageId` 绑定 USER 组件。

---

## 8. 错误码（建议新增）

| 枚举 | HTTP | 说明 |
| --- | --- | --- |
| `AGENT_RUN_NOT_REGENERATABLE` | 409 | run 非终态 |
| `AGENT_MESSAGE_NOT_EDITABLE` | 400 | 非 USER 消息 |
| `AGENT_MESSAGE_NOT_FOUND` | 404 | message/session 不匹配 |
| `AGENT_ACTIVE_RUN_EXISTS` | 409 | 已有进行中 run（若启用） |

---

## 9. 测试用例（`AgentApiTest`）

| # | 场景 | 断言 |
| --- | --- | --- |
| 1 | 完整对话后 `regenerate` | 新 run `RUNNING`→`SUCCESS`；列表 1 USER + 1 ASSISTANT；新 ASSISTANT 的 `run_id` 为新 id |
| 2 | regenerate 后 internal `context` | `history` 不含旧 ASSISTANT 文本 |
| 3 | `edit-regenerate` 截断 | 编辑第 1 轮后，第 2 轮 USER/ASSISTANT 均为 SUPERSEDED，列表不可见 |
| 4 | 对 `RUNNING` run regenerate | 409 |
| 5 | 幂等 `clientRequestId` | 连续两次 regenerate 返回相同 `runId` |
| 6 | 越权 runId / messageId | 404 |
| 7 | `completeRun` 两次（原有用例） | 仍幂等；且仅一条 ACTIVE ASSISTANT per run |

---

## 10. 实施步骤（建议顺序）

| 阶段 | 任务 | 预估 |
| --- | --- | --- |
| P0 | DDL + Entity + `schema-test.sql` | 0.5d |
| P0 | `status` 过滤：`messages` 列表 + `findActiveHistoryBefore` + 改 `context()` | 1d |
| P1 | 抽取 `startRun`；实现 `regenerate` + Controller + OpenAPI | 1d |
| P1 | `completeRun` 旧 ASSISTANT supersede；`supersedeMessagesAfter` | 0.5d |
| P2 | `edit-regenerate` + 幂等 `clientRequestId`（含 `sendMessage`） | 1d |
| P2 | `AgentApiTest` + 更新 `docs/近期变更记录.md` | 0.5d |

**合计**：约 4～5 人日（仅后端 + 契约；不含前端 UI）。

---

## 11. 回滚与兼容

- 新字段默认值 `ACTIVE`，旧客户端仅调原 `sendMessage` 行为不变。
- 新接口未上线前，前端不展示按钮即可。
- 回滚：停新接口，保留字段不影响旧逻辑（列表需带 `status='ACTIVE'` 过滤，回滚代码时去掉过滤会重新显示旧 ASSISTANT——故生产上线建议字段先上、接口后上）。

---

## 12. 与方案 B 的差异（备忘）

| 维度 | 方案 A（本文） | 方案 B |
| --- | --- | --- |
| 编辑 USER | `UPDATE` 同行 | `INSERT` 新 USER，旧 USER `SUPERSEDED` |
| API `messageId` | 稳定 | 每次编辑变化 |
| 审计 | 不保留编辑前原文 | 保留 |

若产品后续要强审计，可在 A 上增加 `agent_message_revisions` 表，而不必整体切 B。

---

## 13. 相关代码索引

| 说明 | 路径 |
| --- | --- |
| 发消息 | `AgentRunServiceImpl.sendMessage` |
| 上下文 | `AgentRunServiceImpl.context` |
| 完成写入 ASSISTANT | `AgentRunServiceImpl.completeRun` |
| 消息 Mapper | `AgentMessageMapper` |
| 任务 regenerate 参考 | `TaskServiceImpl.regenerate` |
| 架构边界 | `docs/系统架构与边界.md` |
