# 2026-05-09 代码推进纪要

> 范围：仓库合并收尾、后端可启动性、双前端本地/Docker 启动链、文档对齐。  
> 关联：`dev` 分支上未推送的本地提交可与本纪要对照；细节接口仍以 `docs/api/openapi.yml` 为准。

---

## 1. 已入库 Git 冲突标记清理

- **现象**：多份 Java、`docs/api/openapi.yml` 等文件中残留 `<<<<<<< HEAD` / `>>>>>>> origin/feature/backend-core`，会导致编译/运行不可预期。
- **处理原则**：后端与 OpenAPI 以 `origin/feature/backend-core` 为 **单一可信版本**（`git checkout origin/feature/backend-core -- <路径>`），去掉冲突标记并统一为 **MyBatis-Plus Mapper + 算力冻结/结算/释放 + 任务幂等与队列** 实现。
- **Worker（手工合并，非整文件覆盖）**：`worker/handlers/text_task_handler.py` —— 保留 `_build_model_prompts` 工具链；无 `userPromptTemplate` 时走 `_build_default_prompt`，避免模板缺失崩溃。

### 1.1 冲突修复文件清单（按路径）

| 路径 | 处理方式 |
|------|----------|
| `backend/.../credit/mapper/CreditMapper.java` | 采用 backend-core：MP `BaseMapper` + `freeze/settle/release/manualAdd/manualDeduct` |
| `backend/.../credit/service/CreditService.java` | 采用 backend-core：接口含冻结/结算/释放、分页日志、人工加减 |
| `backend/.../credit/service/impl/CreditServiceImpl.java` | 采用 backend-core |
| `backend/.../task/mapper/TaskMapper.java` | 采用 backend-core：MP + `@Select/@Update` |
| `backend/.../task/service/impl/TaskServiceImpl.java` | 采用 backend-core |
| `backend/.../task/service/impl/InternalTaskServiceImpl.java` | 采用 backend-core |
| `backend/.../task/dto/TaskDetailResponse.java` | 采用 backend-core |
| `backend/.../task/entity/AiTask.java` | 采用 backend-core |
| `backend/.../tool/mapper/ToolMapper.java` | 采用 backend-core |
| `backend/.../tool/service/impl/ToolServiceImpl.java` | 采用 backend-core |
| `backend/.../tool/entity/ToolFieldSchema.java` | 采用 backend-core |
| `backend/.../tool/entity/ToolFieldItem.java` | 采用 backend-core |
| `backend/.../tool/controller/AdminToolController.java` | 采用 backend-core |
| `backend/.../user/mapper/UserMapper.java` | 采用 backend-core |
| `backend/.../user/controller/AdminUserController.java` | 采用 backend-core |
| `docs/api/openapi.yml` | 采用 backend-core |
| `worker/handlers/text_task_handler.py` | **合并** HEAD 与 backend-core（见上） |

---

## 2. 后端启动：`UserMapper` Bean 缺失

- **现象**：`spring-boot:run` 报 `AuthServiceImpl` 需要 `UserMapper`，但容器中无该 Bean。
- **根因**：`MybatisPlusConfig` 使用 `@MapperScan("com.aiminilab.aitoolmarket.**.mapper")`，`**` 在该扫描路径下 **未按预期展开**，`user.mapper` 等包未被扫描。
- **修复**：改为 `@MapperScan("com.aiminilab.aitoolmarket.*.mapper")`（与当前 `*.mapper` 单层模块包结构一致）。
- **验证**：`mvn test -Dtest=CorsConfigTest` 可拉起完整 Spring 上下文（H2 + `schema-test.sql`）。

---

## 3. 用户端 `user-web`（Vite）

- **现象**：`vite.config.ts` 报无法解析 `@tailwindcss/vite`，开发服务起不来。
- **处理**：在 `user-web` 目录执行 **`npm install`**，确保 `devDependencies` 中的 `@tailwindcss/vite`、`tailwindcss` 进入 `node_modules`。
- **说明**：`user-web` 与 `admin-frontend` **需分别安装依赖**，根目录无统一 `package.json` 时不会自动安装子项目。

---

## 4. 管理端 `admin-frontend`（Next.js）

- **现象 A**：只启动了 `user-web`，管理后台不会自动起来——需单独 `cd admin-frontend && npm run dev`。
- **现象 B**：文档/Compose 曾按 **Vite + 5174** 描述，而实际栈为 **Next.js**（默认曾为 3000），端口与命令不一致。
- **处理**：
  - `admin-frontend/package.json`：`dev` → `next dev -p 5174`；新增 `dev:docker` → `next dev -H 0.0.0.0 -p 5174`。
  - `deploy/docker-compose.yml` 中 `admin-frontend`：命令改为 `npm run dev:docker`；环境变量改为与 `next.config.mjs` 一致的 `NEXT_PUBLIC_API_PROXY_TARGET`，去掉无效的 `VITE_*`。
- **现象 C（Windows）**：`'next' 不是内部或外部命令` → **几乎总是未执行或未完成** `admin-frontend` 下的 **`npm install`**（本地 `node_modules/.bin` 里没有 `next`）。处理：在该目录 **`npm install`** 后再 `npm run dev`。

---

## 5. 未完成 / 建议后续

- **`mvn test` 全量**：曾在环境中被长时间中断，建议在 `backend` 下完整跑一遍并记入 CI。
- **`feature/test-docs` 与 `dev`**：`PROJECT_REVIEW_README.md` 等文档类合并尚未在本纪要对应提交中落地（若需单独 PR，可继续三方合并）。
- **`git push origin dev`**：本地 `dev` 若仍超前远程，在冲突清理与测试通过后再推送，避免远程长期分叉。

---

## 6. 一键启动脚本 `start-dev.bat`

- **步骤**：`[1/6]` Docker MySQL+Redis → `[2/6]` `user-web` 按需 `npm install` → `[3/6]` `admin-frontend` 按需 `npm install` → `[4/6]` 后端新窗口 → `[5/6]` 用户端新窗口（标题 **User-Web**）→ `[6/6]` 管理端新窗口（标题 **Admin-Frontend**）。
- **访问**：用户端 `http://localhost:5173`，管理后台 `http://localhost:5174`（与 `admin-frontend/package.json` 中 `next dev -p 5174` 一致）。

---

## 7. 文档交叉引用

- 管理后台交付清单与历史联调记录：**`docs/admin-v1-progress.md`**（本日已更新「环境 SOP」与头部同步时间，并与本节对齐）。
