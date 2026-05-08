# AI Tool Market

AI Tool Market 是一个基于 **Spring Boot + Vue** 的 AI 工具超市项目。

当前阶段目标是在 7 天内完成 V1 最小可演示版本：管理员可以在后台配置并发布 AI 工具，用户可以在前台选择工具、提交任务，由 Worker 调用 AI 模型生成结果，并在后台追踪任务状态。

## V1 核心链路

```text
管理员登录
→ 后台创建/配置 AI 工具
→ 配置动态字段和 Prompt
→ 发布工具
→ 用户登录
→ 用户看到工具
→ 用户提交任务
→ 后端创建任务并入队
→ Worker 调用 AI
→ 保存结果
→ 用户查看结果
→ 后台查看任务和失败原因
```

## 技术栈


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

```text
ai-tool-market/
├── backend/       # Spring Boot 后端
├── user-web/      # 用户端 Vue
├── admin-web/     # 管理后台 Vue
├── worker/        # Python AI Worker
├── sql/           # 数据库初始化和迁移脚本
├── deploy/        # Docker Compose 和部署配置
├── docs/          # 项目文档
└── README.md
```

## V1 本周不做

```text
支付订单
套餐购买
算力审批流
复杂 RBAC 权限矩阵
完整监控告警系统
文件上传
素材库/RAG
图片/视频/数字人多模态
复杂 Prompt 回滚
多租户/企业空间
```

## 分支约定

```text
main：稳定交付分支
dev：每日集成分支
feature/backend-core：后端核心
feature/user-web：用户端前端
feature/worker-ai：AI Worker
feature/admin-web：管理后台
feature/test-docs：测试文档部署
```

## 每日协作节奏

```text
每天 17:00 前：各成员提交到个人 feature 分支
每天 18:00 前：合并到 dev
每天 20:00 前：测试同学基于 dev 跑核心链路
第 6 天：冻结功能，只修 Bug
第 7 天：本机演示和验收
```

## 第 1 天目标

第 1 天只搭项目骨架，不开发复杂业务：

```text
后端 Spring Boot 能启动
用户端 Vue 能启动
管理后台 Vue 能启动
Worker 能启动
MySQL / Redis 能通过 Docker Compose 启动
前端能请求后端 /api/health
Worker 能消费 Redis 测试消息
```

## 最终验收标准

```text
1. 用户能登录
2. 管理员能登录
3. 管理员能创建并发布一个工具
4. 用户能看到这个工具
5. 用户能提交任务
6. Worker 能完成 AI 生成
7. 用户能看到生成结果
8. 后台能看到任务状态和失败原因
```