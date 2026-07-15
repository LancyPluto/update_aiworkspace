# Mihomo 代理管理页设计

## 目标

在管理后台新增“代理节点”导航和 `/proxy-nodes` 页面，让管理员用机场订阅或海外云服务器代理两种来源维护平台默认出站代理。页面必须清楚区分配置是否已保存、是否已启用以及最近一次连接测试是否通过。

## 产品结构

- 顶部状态区展示当前来源、启用状态、代理出口地址和最近测试结果，并提供“测试连接”主操作。
- 主配置区用分段标签切换“机场订阅”和“云服务器”。切换只改变编辑表单，不会在保存前影响当前生效配置。
- 机场订阅填写配置名称、HTTPS 订阅地址、自动更新间隔和本机 Mihomo 出口地址。订阅地址保存后只返回脱敏值。
- 云服务器填写配置名称、协议（HTTP、HTTPS、SOCKS5）、公网 IP、端口以及可选的用户名和密码。密码保存后不回显。
- 高级设置包含全局启用开关和直连域名列表。保存成功后更新现有 `outbound.proxy.*` 全局设置，现有模型的 `inherit` 代理策略自动继承。

## 数据与接口

- 新增 `GET /api/admin/v1/proxy-config` 读取专用配置视图。
- 新增 `PUT /api/admin/v1/proxy-config` 校验并保存当前来源。空的订阅地址或密码表示保留已保存密钥，显式切换来源会重建默认代理 URL。
- 新增 `POST /api/admin/v1/proxy-config/test` 测试已保存配置。机场订阅检查订阅 URL 可访问性；云服务器检查目标 IP 和端口的 TCP 连通性。响应包含成功标志、延迟和简短诊断。
- 配置复用 `system_settings`，新增 `outbound.proxy.sourceType`、`displayName`、`subscriptionUrl`、`subscriptionUpdateIntervalMinutes`、`mihomoEndpoint`、`manualProtocol`、`manualHost`、`manualPort`、`manualUsername`、`manualPassword`；继续维护既有 `outbound.proxy.url`、`enabledByDefault`、`noProxyHosts`。

## 安全与失败处理

- 订阅仅允许 HTTP/HTTPS 且必须通过现有 SSRF 校验；云服务器必须是可路由公网 IP，拒绝回环、内网、链路本地、云元数据和域名输入。
- 订阅 URL、用户名、密码不写日志；响应只返回 `subscriptionUrlMasked`、`subscriptionConfigured`、`manualUsernameMasked` 和 `manualPasswordConfigured`。
- 保存失败保留表单内容并显示后端错误；测试失败不修改配置，只更新页面测试状态。
- 本版不负责从后端改写宿主机 Mihomo 配置或重启容器。机场方式保存的是 Mihomo 的订阅来源与本机出口地址，运行侧仍由现有部署方式加载 Mihomo；页面不会把“已保存”误写成“已部署”。

## 验收标准

- 侧栏可进入“代理节点”，当前路由有选中态。
- 两种来源均能完成编辑、校验、保存、重新加载和连接测试。
- 手工节点保存后生成可被现有 `OutboundProxyPolicyResolver` 使用的标准代理 URL；机场来源保存后使用配置的 Mihomo 出口地址。
- 敏感字段不会通过专用读取接口明文回显。
- 后端单元/API 测试、管理端 API 单测、TypeScript 类型检查和生产构建通过。
