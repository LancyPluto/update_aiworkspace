# 模型接入域

模型接入域描述外部模型服务的账户归属、服务入口、兼容协议和渠道关系，使配置、计费与公开展示使用一致的语言。

## Language

**Vendor（厂商）**:
对模型账户签发凭据，并承担计费、余额与支持责任的真实运营方。第三方网关是其自身的 Vendor，而不是其兼容的上游品牌。
_Avoid_: 兼容格式、生态分组、`gateway`

**Vendor Account（厂商账户）**:
与一个 Vendor 建立的凭据和结算关系，其归属由实际凭据发行方与账单责任方决定。
_Avoid_: Provider、Protocol

**Provider（服务入口）**:
可被配置和选择的模型服务入口；多个 Provider 可以复用同一种 Protocol。
_Avoid_: Vendor、Protocol

**Protocol（兼容协议）**:
可复用的请求、鉴权与响应契约。兼容某品牌的协议不表示由该品牌运营或计费。
_Avoid_: Vendor、Provider

**Channel Kind（渠道类型）**:
服务入口连接上游的关系类别，例如官方直连或第三方网关。
_Avoid_: Vendor

**Upstream Vendor（上游品牌）**:
Provider 转接或兼容的上游模型品牌，可用于公开生态分组，但不决定 Vendor Account 的归属。
_Avoid_: Vendor、凭据发行方、计费方
