# AI Tool Market 内部签名方法分析报告

**日期：** 2026年6月26日  
**整理人：** Claude (AI Assistant)

---

## 一、内部签名方法的来源

### 谁添加的？

**提交者：** NG666666158 (320401046@qq.com)  
**提交时间：** 2026年5月9日  
**Commit ID：** `e7f823d5`  
**提交信息：** `feat: harden backend persistence and auth`

### 原本使用的是什么？

原本内部服务之间的通信使用**简单的INTERNAL_API_TOKEN明文传递**，没有签名机制。

---

## 二、HMAC-SHA256签名方法详解

### 当前实现

```java
// Backend验证端 (InternalRequestSignatureVerifier.java)
String content = method + "\n" + path + "\n" + timestamp + "\n" + nonce + "\n" + bodyHash;
Mac mac = Mac.getInstance("HmacSHA256");
mac.init(new SecretKeySpec(secret.getBytes(), "HmacSHA256"));
String signature = HexFormat.of().formatHex(mac.doFinal(content.getBytes()));
```

```python
# Worker/Agent签名端 (backend_client.py)
content = f"{method}\n{path}\n{timestamp}\n{nonce}\n{body_hash}"
signature = hmac.new(token.encode(), content.encode(), hashlib.sha256).hexdigest()
```

### 签名组成

| 字段 | 说明 | 示例 |
|------|------|------|
| method | HTTP方法 | GET, POST |
| path | 请求路径 | /api/internal/v1/tasks/119/execution-context |
| timestamp | 毫秒时间戳 | 1719412800000 |
| nonce | UUID随机数 | 550e8400-e29b-41d4-a716-446655440000 |
| bodyHash | 请求体SHA256 | sha256(body) |

### 请求头

```
X-Internal-Timestamp: 1719412800000
X-Internal-Nonce: 550e8400-e29b-41d4-a716-446655440000
X-Internal-Signature: a1b2c3d4e5f6...
```

---

## 三、优缺点分析

### ✅ 优点（安全性提升）

1. **防重放攻击**
   - 使用timestamp + nonce，签名5分钟过期
   - 每个请求的nonce只能使用一次（Redis记录）

2. **防篡改**
   - 签名覆盖method、path、body
   - 任何修改都会导致签名验证失败

3. **防伪造**
   - 只有持有正确token的服务才能生成有效签名
   - 外部攻击者无法伪造内部请求

4. **可追溯**
   - 每个请求都有唯一traceId
   - 便于问题排查和审计

5. **符合安全最佳实践**
   - 类似JWT/OAuth2的设计模式
   - 工业界标准的HMAC-SHA256算法

### ❌ 缺点（运维复杂度）

1. **Token同步困难** ⚠️ **当前遇到的问题**
   - 所有服务（Backend、Worker、Agent Service）必须使用相同的token
   - Docker容器重启后可能读取不到最新的环境变量
   - 需要硬编码到docker-compose.yml或确保.env文件正确同步

2. **调试复杂**
   - 签名验证失败时错误信息不够明确
   - 需要检查多个维度：token、timestamp、nonce、body

3. **时间同步要求**
   - 服务间时钟偏差不能超过5分钟
   - Docker容器可能有时间同步问题

4. **性能开销**
   - 每个请求都需要计算HMAC-SHA256
   - 需要Redis存储nonce防重放

5. **配置管理**
   - token不能动态更新，需要重启服务
   - 缺少token轮换机制

---

## 四、对比：旧方案 vs 新方案

| 维度 | 旧方案（明文Token） | 新方案（HMAC签名） |
|------|---------------------|-------------------|
| **安全性** | ❌ 低（token明文传输，可被截获重放） | ✅ 高（防重放、防篡改） |
| **实现复杂度** | ✅ 简单 | ❌ 复杂 |
| **运维难度** | ✅ 简单（只需同步一个token） | ❌ 较高（需要处理签名逻辑） |
| **调试难度** | ✅ 简单 | ❌ 较高 |
| **性能** | ✅ 无开销 | ⚠️ 轻微开销 |
| **适用场景** | 内网可信环境 | 需要高安全性的场景 |

---

## 五、建议

### 短期（立即）

1. **文档化token管理流程**
   - 在README中明确说明token配置方法
   - 提供token同步的检查清单

2. **添加诊断工具**
   - 编写脚本检查所有服务的token是否一致
   - 添加签名验证的调试日志

### 中期（1-2周）

1. **简化配置**
   - 考虑使用Docker Secrets或环境变量管理工具
   - 实现token的热更新机制

2. **增强错误信息**
   - 签名验证失败时返回更详细的错误原因
   - 区分"token不匹配"、"timestamp过期"、"nonce重复"等

### 长期（1-2月）

1. **考虑替代方案**
   - 如果是纯内网环境，可以考虑简化为mTLS或API Gateway
   - 或使用JWT（自带过期时间，更标准化）

2. **实现token轮换**
   - 支持多个token并存（新旧token过渡期）
   - 自动token更新机制

---

## 六、今天的修复记录（2026-06-26）

### 问题解决

| 问题 | 根因 | 修复方案 | 状态 |
|------|------|----------|------|
| 撤回公开作品失败 | assetFromTask缺少communityPostId | 添加字段传递 | ✅ |
| 下载作品跳转而非下载 | 跨域时<a download>失效 | 改用fetch+blob下载 | ✅ |
| 28MB视频上传失败 | nginx+Spring限制25MB | 提升到100MB | ✅ |
| OSS文件缺失时撤回失败 | NoSuchKey导致整个操作失败 | 捕获异常允许继续 | ✅ |
| Vidu厂商配置 | 缺少model_config和映射 | 添加配置和Provider映射 | ✅ |
| 厂商Logo迁移到OSS | 使用Next.js静态文件 | 批量上传到OSS | ✅ |
| Agent卡在"思考" | Worker/Agent Token不一致 | 同步docker-compose.yml配置 | ✅ |

### 关键发现

- **INTERNAL_API_TOKEN必须在所有服务间保持一致**
- Docker容器重启时需要确保环境变量正确加载
- 使用 `docker compose` (新版) 而不是 `docker-compose` (旧版)

---

## 七、社区页面悬停播放视频功能

### 功能实现者

**提交者：** yangzx  
**Commit：** `23350a01`  
**提交信息：** `feat：标题字体美化；社区页面与收藏页面美化`

### 功能说明

用户社区页面的视频卡片支持：
- 鼠标悬停时自动播放视频
- 鼠标移出时暂停/停止播放
- 使用`playingPostId`状态变量控制

---

## 八、代码推送状态

- ✅ 所有修复已推送到 `feature/test-docs` 分支
- ⏳ 需要创建PR合并到 `dev` 分支

---

**文档生成时间：** 2026-06-26 18:15  
**相关Commit：** 92a96944, e808ff5a, 81b8f42b, 4ad3cdaa, ab4568f7, 0e7ac6a0, 86ce39f4, ac584ea9
