# 当前 Agent 链路梳理（小学生也能看懂版）

## 先说一句

可以把这个项目的 agent 想成三位“员工”：

- **门卫（入口）**：收到任务就拿取上下文（RunContext）；
- **老师（决策）**：判断这次是“直接聊”还是“去调用工具”；
- **小工人（工具执行 + 回答）**：执行工具、整理答案、再把信息存进“记忆库”。

---

## 一次请求的主线（总体流程）

```mermaid
flowchart TD
  A["1) 外部发起 run 执行 /tools/internal/v1/agent/runs/{run_id}/execute"] --> B["2) AgentRuntime.execute_run: 取 run 上下文 get_run_context"]
  B --> C["3) 选运行时：LangGraphRuntimeEngine 包住 DeepAgentsRuntimeEngine 或 DeepAgents 关闭时走旧逻辑"]
  C --> D["4) DeepAgentsRuntimeEngine.run: 安全过滤 + 意图分类"]
  D --> E{"5) 分类结果"}
  E -->|"FILE_ANALYSIS / GENERAL_CHAT / UNSUPPORTED(可回退)"| F["_run_chat：构建 prompt、带入历史/文件/记忆，模型回答"]
  E -->|"TOOL_USE"| G["_handle_tool_use：参数整理、是否需要确认"]
  G --> H{"工具要不要直接执行?"}
  H -->|"是"| I["_execute_tool_with_guard -> ToolOrchestrator -> BackendToolBridge -> 发给后端工具/API"]
  H -->|"否/缺参数"| J["回复澄清问题，等待用户补充"]
  I --> K["_synthesize_answer：把工具结果转成人类可读回答"]
  J --> L["_complete_run 结束本轮"]
  K --> L
  F --> L
  L --> M{"本轮是否触发记忆整理"}
  M -->|"是"| N["WorkspaceMemoryRuntime.curate_after_run（候选/直接保存）"]
  M -->|"否"| O["返回"]
  N --> O
```

---

## 先判断意图：是聊天还是工具

### 意图决策链（decision service）

1. 先看是不是明文聊天场景（短句问候、追问、上文回溯提示）=> **GENERAL_CHAT**
2. 再做规则路由（`IntentRouter.classify`）
3. 规则不够清晰时，调用可选的 LLM 路由（`AgentRouterService`）再一次二次判断
4. 最终产出 `IntentResult`（意图+置信度+候选工具）

```mermaid
flowchart LR
  A["RunContext 进入"] --> B["_conversation_guard（会话保护）"]
  B -->|是聊天/追问| C["返回 GENERAL_CHAT"]
  B -->|不是| D["IntentRouter.classify（规则）"]
  D --> E{"是否硬路由规则足够确定?"}
  E -->|是| F["返回规则结果"]
  E -->|否| G["AgentRouterService.classify（LLM 路由器）"]
  G --> H["最终 IntentResult"]
```

### 决策的关键字段（给前端排障很有用）

| 字段 | 来源 | 作用 |
|---|---|---|
| `intent` | `IntentResult` | 本轮要走聊天还是工具 |
| `selectedToolCode` | 规则/LLM 路由 | 优先选择的工具编码 |
| `candidateToolCodes` | 规则/LLM 路由 | 可选工具列表 |
| `clarifyingQuestion` | 路由层 | 不确定时向用户追问 |
| `requiresConfirmation` | 追溯 + 风险判定 | 是否需要用户确认 |

---

## 工具调用链（如果意图是 TOOL_USE）

```mermaid
flowchart TD
  A["_handle_tool_use"] --> B["Followup 续接：是否沿用上一步参数"]
  B --> C["ToolDecisionValidator：权限、参数/风险二次校验"]
  C --> D{"是否缺参数"}
  D -->|"缺参数"| E["tool_bridge.enrich_arguments -> 提示用户补参"]
  D -->|"参数够了"| F["判断 auto_call"]
  E --> G{"auto_call?"}
  G -->|"否"| H["TOOL_CONFIRMATION_REQUIRED -> 等用户确认"]
  G -->|"是"| I["_execute_tool_with_guard"]
  F -->|"是"| I
  I --> J["ToolOrchestrator.execute_with_guard"]
  J --> K["backend.create_task + poll + complete/fail"]
  K --> L["tool result 回到 _synthesize_answer"]
  H --> M["返回澄清答案，run 结束"]
  L --> N["_complete_run"]
  M --> N
```

### 工具阶段里的“看门人”职责

| 组件 | 负责内容 |
|---|---|
| `ToolDecisionValidator` | 风险分级、确认策略、跟进参数策略 |
| `ToolOrchestrator` | 本地参数拼装、必填检查、预算占用前置 |
| `BackendToolBridge` | 与后端工具 API 通信，创建任务并回填结果 |

---

## 记忆链路（你前面问的“有无跨对话”核心）

记忆不是保存在“聊天窗口内存”里，而是按 `workspaceId` 落到“工作区记忆库”。

### 记忆如何“进来”（读取）

```mermaid
flowchart TD
  A["_run_chat / _synthesize_answer"] --> B["_fetch_workspace_memory_context"]
  B --> C["memory_runtime.fetch_items"]
  C --> D["backend.retrieve_workspace_memory(workspace_id, query, limit, view)"]
  D --> E["format_workspace_memory_context"]
  E --> F["注入到模型 system prompt（冻结快照）"]
```

### 记忆如何“存进去”（保存）

```mermaid
flowchart TD
  A["_stream_model_answer(带 memory tools 时)"] --> B["model 可能调用 memory_add/memory_replace/memory_remove"]
  A["_run_tool/synthesize 后"] --> C["MemoryCuratorService 自动判定（回退式）"]
  B --> D["WorkspaceMemoryRuntime.curate_after_run"]
  C --> D
  D --> E{"action=add/update/candidate"}
  E -->|"add"| F["backend.create_workspace_memory"]
  E -->|"update"| G["backend.update_workspace_memory"]
  E -->|"candidate"| H["backend.create_workspace_memory_candidate"]
```

### 一句话看懂“跨对话”真假

- **跨对话生效**：同一个 `workspaceId` 下，下次请求会再取到同一份记忆。
- **看起来不生效**：通常是因为 `workspaceId` 不一致、这次没写入成功、或写入是“候选未审核/未固化”。

---

## 关键文件（你可以直接对照）

| 文件 | 作用 |
|---|---|
| `agent-service/app/core/runtime.py` | run 入口，取上下文、选模型、选引擎 |
| `agent-service/app/runtime/router.py` | runtime 选择（LangGraph/DeepAgents） |
| `agent-service/app/runtime/deep_agents_engine.py` | 主要执行流程（意图、工具、聊天、归档） |
| `agent-service/app/core/agent_decision.py` | 决策融合（规则 + LLM + 会话保护） |
| `agent-service/app/core/intent_router.py` | 规则路由分类 |
| `agent-service/app/core/schemas.py` | `RunContext`、`RunEventCreate`、`WorkspaceMemoryItem` |
| `agent-service/app/runtime/memory_runtime.py` | 记忆读取 + 归档调度入口 |
| `agent-service/app/runtime/memory_curator.py` | 候选/自动记忆归档策略 |
| `agent-service/app/tools/memory_tool.py` | memory tool（add/replace/remove） |
| `agent-service/app/clients/backend_client.py` | 与后端 memory API 交互 |

---

## 给同学版的“极简总结”

`用户问题 -> 取上下文 -> 判断意图 -> 要么聊要么用工具 -> 回答用户 -> 有价值信息写入 workspace 记忆 -> 下次再用。`

如果你要，我可以再给你做一版“排查清单版”流程图（比如“为什么这次明明写了记忆但下次没命中”），按红黄绿三色步骤标注。
