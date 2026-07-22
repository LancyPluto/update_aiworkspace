import assert from "node:assert/strict"
import { readFile } from "node:fs/promises"
import test from "node:test"

const source = await readFile(new URL("./unified-api-settings.tsx", import.meta.url), "utf8")

test("model health presentation is independent from account health", () => {
  assert.match(source, /function modelRowTone\(model: UnifiedApiModelItem\)[\s\S]*?const health = model\.healthStatus/)
  assert.doesNotMatch(source, /function canEnableAgentForModel/)
  assert.doesNotMatch(source, /toggleModelEnabled|patchModelEnabled|togglingModelId/)
  assert.doesNotMatch(source, /模型已停用|enabledModelCount|enabledRate|MODEL_DISABLED/)
  assert.doesNotMatch(source, /function modelAccountHealth/)
  assert.doesNotMatch(source, /function accountProbePassed/)
})

test("account cards expose explicit routing controls and runtime state", () => {
  assert.match(source, /updateModelVendorAccountRouting/)
  assert.match(source, /负载均衡/)
  assert.match(source, /loadBalanceWeight/)
  assert.match(source, /routingPoolNameDrafts/)
  assert.match(source, /请先填写池名称/)
  assert.match(source, /输入或选择同厂商负载池/)
  assert.match(source, /在途 \{Math\.max\(0, account\.inFlightCount/)
  assert.match(source, /circuitStatusBadge\(account\)/)
  assert.match(source, /routingExclusionReason/)
  assert.match(source, /ACCOUNT_DISABLED: "账户已停用"/)
  assert.match(source, /LOAD_BALANCING_DISABLED: "未开启负载均衡"/)
  assert.match(source, /CIRCUIT_OPEN: "账户处于熔断冷却中"/)
  assert.match(source, /PRICE_MISMATCH: "同名模型价格配置不一致"/)
  assert.match(source, /ACCOUNT_POOL_MISMATCH: "模型锚点账户不属于所选负载池"/)
  assert.match(source, /路由提示/)
})

test("vendor headers stay group-only while account cards keep issue details", () => {
  const vendorHeader = source.match(/function renderVendorSection[\s\S]*?<CollapsibleContent/)?.[0] || ""
  const accountBadge = source.match(/function accountHealthBadge[\s\S]*?function formatHealthCheckedAt/)?.[0] || ""

  assert.doesNotMatch(vendorHeader, /balanceStatusBadge\(primaryAccount\)/)
  assert.doesNotMatch(vendorHeader, /accountHealthBadge\(primaryAccount\)/)
  assert.doesNotMatch(vendorHeader, /toggleAccountEnabled\(primaryAccount/)
  assert.match(vendorHeader, /vendor\.accounts\.length\} 个账户 · \{vendor\.models\.length\} 个模型/)
  assert.doesNotMatch(accountBadge, /连通正常/)
  assert.match(source, /凭据有效，有告警/)
  assert.doesNotMatch(source, /account\.enabled \? "已启用" : "已停用"/)
  assert.doesNotMatch(source, /\{account\.modelCount\} 个模型/)
})

test("model editor can target either an account or a same-vendor routing pool", () => {
  assert.match(source, /<Label>路由目标<\/Label>/)
  assert.match(source, /value=\{`account:\$\{account\.id\}`\}/)
  assert.match(source, /value=\{`pool:\$\{pool\.id\}`\}/)
  assert.match(source, /disabled=\{pool\.eligibleAccounts\.length === 0\}/)
  assert.match(source, /resolveModelRoutingTarget\(value, form\.vendorAccountId, modelAccountOptions\)/)
  assert.match(source, /<TableHead className="w-\[170px\] text-center">路由目标<\/TableHead>/)
})
