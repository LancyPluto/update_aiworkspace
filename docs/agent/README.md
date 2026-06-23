# Agent 文档索引

本目录是 Agent 编排（agent-service + 后端 Agent 事实源 + 用户端 Agent UI）的文档入口。维护或排障时请先从这里进入。

## 主事实源（请优先看这两篇）

| 文档 | 用途 |
|---|---|
| [../../agent.md](../../agent.md) | 后续 Agent 写代码前必须遵守的工程红线，尤其是多模态上下文、`@图片` 指针和模态判断 |
| [架构与维护指南.md](./架构与维护指南.md) | Agent 当前架构的权威说明：三引擎、graph 多步循环、安全边界、HITL + checkpoint、事件契约、文件地图、测试与灰度回滚、维护清单 |
| [链路梳理（小学生版）.md](./链路梳理（小学生版）.md) | 通俗易懂的一次请求链路讲解，用于快速理解与新人交接 |
| [工具注册Agent-Skill设计方案.md](./工具注册Agent-Skill设计方案.md) | 后台工具注册改成 Skill 式渐进披露的设计方案，含元 skill、字段策略编译和一键生成草稿契约 |

代码侧补充：

- [../../agent-service/README.md](../../agent-service/README.md)：agent-service 的本地运行、依赖、引擎开关说明。
- [../参考图解析链路与多图保留-2026-06-13.md](../参考图解析链路与多图保留-2026-06-13.md)：参考图、`@图片` 指针、历史生成图和 Worker 传参顺序的排障文档。
- [../项目整体架构说明.md](../项目整体架构说明.md)：全系统架构，含 Agent 服务在整体中的位置。

## 历史记录（仅供追溯，不作为当前事实源）

| 文档 | 说明 |
|---|---|
| [../Agent优化与工作区整理-2026-06-04.md](../Agent优化与工作区整理-2026-06-04.md) | 一轮 Agent / 记忆 / User Web 整理记录 |
| [../archive/2026-06-cleanup/agent-tool-health-and-memory-plan.md](../archive/2026-06-cleanup/agent-tool-health-and-memory-plan.md) | 工具健康度与记忆方案（已归档） |
| [../archive/2026-06-cleanup/Agent参数化与记忆管理落地记录-2026-06-01.md](../archive/2026-06-cleanup/Agent参数化与记忆管理落地记录-2026-06-01.md) | 参数化与记忆管理落地记录（已归档） |
| [../archive/2026-06-cleanup/Agent记忆与ToolCall落地记录-2026-05-31.md](../archive/2026-06-cleanup/Agent记忆与ToolCall落地记录-2026-05-31.md) | 记忆与 ToolCall 落地记录（已归档） |
| [../archive/2026-06-cleanup/Agent落地收尾记录-2026-05-27.md](../archive/2026-06-cleanup/Agent落地收尾记录-2026-05-27.md) | 早期落地收尾记录（已归档） |

## 维护约定

- 当改动 Agent 引擎、节点、事件类型、checkpoint 契约或安全边界时，请同步更新 [架构与维护指南.md](./架构与维护指南.md)；其末尾的「维护清单」列出了每类改动需要一起更新的文件与测试。
- 当改动多模态上下文、参考图、工具 schema、输出模态判断或 Worker 图片入参时，请先读 [../../agent.md](../../agent.md)，并同步更新参考图链路文档。
- 当改动后台工具注册、字段策略、tool descriptor 渐进披露或 Agent Skill 草稿生成时，请同步更新 [工具注册Agent-Skill设计方案.md](./工具注册Agent-Skill设计方案.md)。
- 通俗讲解类改动写进 [链路梳理（小学生版）.md](./链路梳理（小学生版）.md)，保持其易懂风格。
- 一次性的整理 / 上传记录请新建带日期的记录文档，并在「历史记录」表中登记，不要覆盖主事实源。
