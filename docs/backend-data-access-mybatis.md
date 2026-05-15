# 后端数据访问层说明

## 当前结论

当前后端已经从早期审查报告中的 `JdbcTemplate` 模式，演进为以 MyBatis-Plus 为主的数据访问方式。

主要使用方式：

- 实体类使用 `@TableName`、`@TableId`、`@TableField` 映射数据库表和字段。
- Mapper 接口继承 `BaseMapper<T>`，获得基础的 `insert`、`selectById`、`updateById`、`selectList` 能力。
- 复杂查询、联表查询、状态流转更新，使用 MyBatis 注解 SQL，例如 `@Select`、`@Insert`、`@Update`、`@Delete`。
- `JdbcTemplate` 只保留在健康检查、启动数据补丁、测试辅助等少量场景，不作为业务 CRUD 的主路径。

## 数据库操作链路

标准链路应保持为：

```text
Controller -> Service -> Mapper -> MySQL
```

职责边界：

- Controller 只处理请求入参、认证上下文和响应包装。
- Service 承担事务、业务校验、权限校验、状态机判断和跨 Mapper 编排。
- Mapper 只负责数据库读写，不承载业务决策。
- Worker 不直接操作数据库，只通过 Backend 内部 API 回写任务状态和结果。

## MyBatis-Plus 配置

配置入口：

- `backend/src/main/java/com/aiminilab/aitoolmarket/config/MybatisPlusConfig.java`
- `backend/src/main/resources/application.yml`

当前配置重点：

- 显式扫描各业务模块下的 `mapper` 包。
- 开启分页插件 `PaginationInnerInterceptor(DbType.MYSQL)`。
- 开启下划线到驼峰映射 `map-underscore-to-camel-case: true`。
- 全局主键策略为自增 `id-type: auto`。

## 使用规范

建议保留 MyBatis-Plus，不急着迁移 JPA。

原因：

- 当前项目 SQL 较多涉及任务状态流转、余额冻结/扣减、Agent 运行记录、模型配置联表查询，手写 SQL 更可控。
- MyBatis-Plus 能减少简单 CRUD 样板，同时允许复杂查询显式写 SQL。
- 比 JPA 更适合当前这种偏运营后台、任务系统、审计流水类业务。

约束：

- 简单单表 CRUD 优先用 `BaseMapper` 和 `LambdaQueryWrapper`。
- 联表查询、条件分页、状态机更新可以用注解 SQL。
- 不建议在 Service 中拼 SQL。
- 不建议 Worker、前端或 Agent 服务直接连数据库。
- 涉及余额、任务状态、默认模型配置的写操作必须放在 Service 事务内。
- 软删除表要统一使用 `is_deleted = 0` 过滤，后续逐步补齐 `@TableLogic`。

## 当前需要继续治理的问题

1. 软删除规则还不统一：部分实体有 `@TableLogic`，部分 Mapper 依赖手写 `COALESCE(is_deleted, 0) = 0`。
2. 分页有插件，但部分查询仍手写 `LIMIT/OFFSET`，后续可逐步统一为 MyBatis-Plus `Page`。
3. 注解 SQL 已经较多，复杂度继续上升后，建议把超长 SQL 迁移到 XML Mapper。
4. 任务状态、积分账户、模型配置属于核心一致性区域，应补充 Mapper 层或 Service 层专项测试。
5. `PROJECT_REVIEW_README.md` 仍是早期审查视角，且当前显示存在编码问题，应更新为今天的实际架构状态。
