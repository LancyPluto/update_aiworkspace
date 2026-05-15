# 11. Git 提交与代码评审规则

本文用于避免 5 人并行开发时出现合并混乱。

## 1. 分支

```text
main：最终交付
dev：每日集成
feature/backend-core：后端核心
feature/user-web：用户端前端
feature/worker-ai：Worker
feature/admin-web：管理后台
feature/test-docs：测试文档部署
```

## 2. 提交信息格式

```text
feat: 新增功能
fix: 修复 Bug
docs: 文档
test: 测试
chore: 配置/脚本
refactor: 重构
```

示例：

```text
feat: add task creation api
fix: prevent duplicate credit deduction
docs: update worker startup guide
```

## 3. 每日提交要求

```text
每天至少提交一次
提交前本地能启动自己负责模块
不要提交 .env.local
不要提交模型 Key
不要提交无关格式化大改
```

## 4. Pull Request 检查项

合并到 `dev` 前检查：

```text
是否改了接口字段
是否改了数据库字段
是否影响其他成员
是否更新了文档
是否能本地启动
是否有测试说明
```

## 5. 禁止事项

```text
禁止直接推 main
禁止最后一天才合并
禁止私自改统一契约
禁止把大功能塞进第 7 天
禁止绕过后端接口直接改数据库造演示结果
```

## 6. 冲突处理

```text
接口冲突：1 号裁决
页面交互冲突：2 号/4 号先协商，1 号确认
Worker 与后端状态冲突：1 号和 3 号当天解决
测试阻塞：5 号标 P0，责任人当天处理
```

