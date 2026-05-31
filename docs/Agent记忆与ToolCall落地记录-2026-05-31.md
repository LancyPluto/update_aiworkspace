# Agent 记忆与 Tool-Call 落地记录

更新时间：2026-05-31

## 本轮目标

把 Agent 从“触发词驱动的记忆和路由”推进到更适合商业化助手的结构化机制：

- Agent 模型负责理解、对话、记忆判断和轻量编排。
- 后端继续作为事实源，负责权限、配置、校验、落库和任务状态。
- 工具执行仍走现有 backend/worker 链路，避免模型绕过费用、权限和确认策略。
- 长期记忆保持少而准，会话历史和工具结果通过检索进入上下文。

## 已落地能力

### OpenAI Tool-Call Loop

`agent-service` 新增非流式结构化调用路径：

- `ModelClient.chat_turn(messages, tools, tool_choice)` 统一返回 `content`、`toolCalls`、`finishReason`、`usage`、`raw`。
- OpenAI-compatible 路径解析 `message.tool_calls`。
- LangChain 路径解析 `AIMessage.tool_calls` 与 `additional_kwargs.tool_calls`。
- 保留原有 `chat()` 和 `chat_stream()`，最终可见回复仍可以走流式展示。

新增 `AgentToolCallLoopExecutor`：

- 默认只开放安全记忆工具：`memory_add`、`memory_replace`、`memory_remove`。
- 未知工具、禁用工具、非法参数会被拒绝，并以 tool result 形式回传模型。
- 工具调用完成后再让模型生成最终用户可见回复。
- 产品生成工具暂不直接暴露给模型，后续应通过受控 meta-tool 进入现有 Router/Validator/BackendToolBridge。

### 记忆写入

新增或完善：

- `MemoryTool`
- `MemoryCuratorService`
- `agent_workspace_memory_items` 扩展字段
- `memory/candidates` 内部接口
- 用户侧记忆列表、编辑、删除、置顶能力

当前策略：

- 明确“记住/写入记忆”的请求优先通过 tool-call loop 写入。
- tool-call loop 未写入时，fallback curator 会根据最终回复做低风险补救。
- “你觉得我是什么样的人？写入你的记忆里”保存的是助手总结后的用户画像，而不是原始问题文本。
- 图片 URL、大 JSON、一次性 prompt、临时闲聊不会进入长期记忆。
- 低置信内容进入候选，不直接污染长期记忆。

### 记忆读取

Agent 每轮构建 frozen memory snapshot：

- 固定优先级：当前用户指令 > 实时工具结果 > recentToolCalls > 长期记忆。
- `user_profile`、`preference` 进入用户画像区。
- `workspace_fact` 进入工作区事实区。
- `tool_lesson`、`workflow_recipe` 进入流程经验区。

本轮修复了一个关键问题：

- 新建对话问“我是何人？”时，后端确实调用了 `/memory/retrieve`，但 query 与画像内容没有词面重合，FULLTEXT 和子串检索可能返回空。
- 现在当精确检索无结果时，会返回一个高价值 context pack：置顶、高重要度、`user_profile`、`preference`、`workflow_recipe`。
- Agent 侧也把“我是谁 / 我是何人 / 用户画像 / 个人画像 / 你了解我”等问题固定为 chat 记忆视图，避免被 recentToolCalls 推到 router 视图。

### 周期性整理

新增轻量 consolidation：

- 默认每 8 个用户回合触发。
- 上下文累计超过阈值触发。
- 连续完成多次同类工具任务时触发。
- 优先更新已有画像/偏好，避免重复新增。

第一阶段仍是轻量启发式整理，后续可升级为 LLM curator，但后端校验和候选机制应保持不变。

## 新增观测事件

- `tool_call.loop_started`
- `tool_call.requested`
- `tool_call.executed`
- `tool_call.rejected`
- `tool_call.loop_completed`
- `memory.curator_started`
- `memory.candidate_created`
- `memory.saved`
- `memory.updated`
- `memory.consolidated`
- `memory.retrieved`
- `memory.context_frozen`

后台运行详情可查看 tool-call loop、记忆写入、候选、检索命中和 context pack 来源。用户端继续保持简洁，不暴露冗长调试链。

## 关键文件

- `agent-service/app/clients/model_client.py`
- `agent-service/app/runtime/tool_call_loop.py`
- `agent-service/app/tools/memory_tool.py`
- `agent-service/app/runtime/memory_curator.py`
- `agent-service/app/runtime/deep_agents_engine.py`
- `backend/src/main/java/com/aiminilab/aitoolmarket/agent/service/impl/AgentWorkspaceServiceImpl.java`
- `backend/src/main/java/com/aiminilab/aitoolmarket/agent/config/AgentMemorySettings.java`
- `admin-frontend/app/agent-runs/page.tsx`

## 验证结果

- `pytest agent-service/tests -q`：181 passed。
- `mvn test -Dtest=AgentWorkspaceApiTest -q`：passed。
- `npm run build` in `admin-frontend`：passed。
- `npm run build` in `user-web`：passed。

## 后续建议

1. 将产品生成工具统一包进受控 `request_tool_execution` meta-tool，再接入 OpenAI tool-call loop。
2. 后台 Agent 配置页继续完善 Tools / Toolsets 管理，展示风险等级、自动调用、字段策略和模型绑定状态。
3. 记忆检索 v1 继续用 MySQL FULLTEXT + context pack，真实数据量上来后再加 embedding 混合检索。
4. 把 memory candidate 在用户端做成明确的“待确认记忆”，避免自动记忆让用户失去控制感。
5. 持续收集 `tool_call.rejected`、`memory.rejected`、`router.fallback` 事件，用真实失败样本改进 schema 与提示词。
