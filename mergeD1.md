# 第 1 天合并测试报告（Merge D1）

> 报告人：测试验证人员
> 日期：2026-05-08
> 关联：合并到 `dev` 分支前的前后端联调验证

---

## 一、环境验证结果

### 1.1 基础设施 ✅

| 组件 | 状态 | 端口 |
|------|------|------|
| MySQL (`docker-compose up -d mysql`) | ✅ 正常运行 | 3307 → 3306 |
| Redis (`docker-compose up -d redis`) | ✅ 正常运行 | 6379 |
| Admin Frontend (`docker-compose up -d admin-frontend`) | ✅ 正常运行 | 5174 |

### 1.2 后端服务 ✅

| 项目 | 状态 | 端口 |
|------|------|------|
| Spring Boot (`mvn spring-boot:run`) | ✅ 启动成功 | 8080 |
| MySQL 连接池 | ✅ HikariPool 初始化完成 |
| Redis 连接 | ✅ 正常 |

### 1.3 前端服务 ✅

| 项目 | 状态 | 端口 |
|------|------|------|
| User Web (`npm run dev`) | ✅ 启动成功 | 5173 |
| Admin Frontend (Docker) | ✅ 启动成功 | 5174 |

### 1.4 健康检查接口 ✅

```
GET http://localhost:8080/api/health

响应：
{
  "code": "SUCCESS",
  "message": "ok",
  "data": {
    "service": "backend",
    "mysql": "ok",
    "redis": "ok"
  }
}
```

✅ 后端、MySQL、Redis 三方连通验证通过。

---

## 二、发现的问题及修复

### 2.1 【Bug】User-Web 登录请求字段名与后端不匹配

**问题描述：**

后端 `LoginRequest` DTO 的字段定义为 `account`，而 `user-web` 前端发送登录请求时使用的字段名为 `username`，导致 `@Valid` 校验失败，接口始终返回 `PARAM_ERROR`。

- `admin-frontend` 正确使用了 `account`，不存在此问题
- `RegisterRequest` 使用 `username`（注册场景，字段名语义合理，无需修改）

**请求链路（修复前）：**

```
前端（user-web）LoginView.vue
  ↓ POST { username, password }          ← 字段名错误
  axios → http.js（baseURL = /api/v1）
    ↓ Vite proxy → http://localhost:8080
    后端 AuthController.login(@Valid @RequestBody LoginRequest)
      ↓ @NotBlank String account         ← JSON 中无 "account" 字段
    校验失败 → MethodArgumentNotValidException
      ↓
    GlobalExceptionHandler → {"code":"PARAM_ERROR","message":"参数错误"}
```

**修复方案：**

保持后端 `LoginRequest.account` 不变（与 `admin-frontend` 一致），将 `user-web` 登录请求的字段名改为 `account`。

**涉及文件及修改：**

| 文件 | 修改前 | 修改后 |
|------|--------|--------|
| `user-web/src/views/LoginView.vue:30` | `username: form.value.username.trim()` | `account: form.value.username.trim()` |
| `user-web/src/views/LoginView.vue:44` | `username: form.value.username.trim()` | `account: form.value.username.trim()` |

**修改前后对比：**

```javascript
// 修复前（user-web 登录请求）
await auth.login({
  username: form.value.username.trim(),   // ← 字段名不对
  password: form.value.password,
})

// 修复后（与后端 LoginRequest.account 对齐）
await auth.login({
  account: form.value.username.trim(),    // ← 改为 account
  password: form.value.password,
})
```

### 2.2 各端字段名对照（统一使用 `account`）

| 端 | 文件 | 字段名 | 是否合规 |
|----|------|--------|---------|
| 后端 DTO | `LoginRequest.java` | `account` | ✅ 统一标准 |
| 后端 Service | `AuthServiceImpl.java` | `request.account()` | ✅ 一致 |
| User-Web 登录 | `LoginView.vue` | ~~`username`~~ → `account` | ✅ 已修复 |
| Admin-Frontend 登录 | `auth.ts` | `account` | ✅ 正确无需改 |

---

## 三、总结

### 3.1 修改清单

| 序号 | 文件路径 | 修改内容 | 状态 |
|------|---------|---------|------|
| 1 | `user-web/src/views/LoginView.vue`（登录模式） | `username` → `account` | ✅ 已修复 |
| 2 | `user-web/src/views/LoginView.vue`（注册后自动登录） | `username` → `account` | ✅ 已修复 |

> 后端 `LoginRequest.java` 和 `AuthServiceImpl.java` **未修改**，保持原有的 `account` 命名。

### 3.2 经验教训与规范建议

1. **前端字段名必须与后端 DTO 一致**：API 契约以后端为准，前端应严格对齐。
2. **`account` vs `username` 命名场景**：
   - 登录场景使用 `account`（后端 `LoginRequest` 定义）
   - 注册场景使用 `username`（后端 `RegisterRequest` 定义）
   - 两个场景的业务含义不同，字段名合理分离
3. **联调前先通读后端接口文档/openapi.yml**，避免凭直觉猜字段名。

### 3.3 新增的一键启动脚本

新增 `start-dev.bat`，一行命令即可拉起全部环境：

```bash
# 在项目根目录双击运行，或：
start start-dev.bat
```

脚本功能：
| 步骤 | 操作 | 说明 |
|------|------|------|
| 1/5 | 前置检查 | Docker / Java / Maven / npm 环境检测 |
| 2/5 | Docker 容器 | 启动 MySQL + Redis，等待就绪 |
| 3/5 | 前端依赖 | 自动安装 `npm install`（如需要） |
| 4/5 | 后端服务 | 新窗口运行 `mvn spring-boot:run` |
| 5/5 | 前端服务 | 新窗口运行 `npm run dev` |

### 3.4 测试通过的功能

- [x] Docker 容器化环境（MySQL / Redis）正常运行
- [x] 后端 Spring Boot 启动并连接数据库 + Redis
- [x] 健康检查接口 `/api/health` 返回 SUCCESS
- [x] 前端 User Web Vite Dev Server 启动正常
- [x] 前后端代理配置正常（`/api` → `localhost:8080`）
- [x] 新增 `start-dev.bat` 一键启动脚本
