import assert from "node:assert/strict"
import { readFile } from "node:fs/promises"
import test from "node:test"

const source = await readFile(new URL("./unified-api-settings.tsx", import.meta.url), "utf8")

test("model health presentation is independent from account health", () => {
  assert.match(source, /function modelRowTone\(model: UnifiedApiModelItem\)[\s\S]*?const health = model\.healthStatus/)
  assert.match(source, /function canEnableAgentForModel\(model: UnifiedApiModelItem\) \{\s*return model\.enabled\s*\}/)
  assert.doesNotMatch(source, /function modelAccountHealth/)
  assert.doesNotMatch(source, /function accountProbePassed/)
})

test("account cards expose explicit routing controls and runtime state", () => {
  assert.match(source, /updateModelVendorAccountRouting/)
  assert.match(source, /负载均衡/)
  assert.match(source, /loadBalanceWeight/)
  assert.match(source, /在途 \{Math\.max\(0, account\.inFlightCount/)
  assert.match(source, /circuitStatusBadge\(account\)/)
  assert.match(source, /routingExclusionReason/)
  assert.match(source, /ACCOUNT_DISABLED: "账户已停用"/)
  assert.match(source, /LOAD_BALANCING_DISABLED: "未开启负载均衡"/)
  assert.match(source, /CIRCUIT_OPEN: "账户处于熔断冷却中"/)
  assert.match(source, /PRICE_MISMATCH: "同名模型价格配置不一致"/)
  assert.match(source, /未入负载池/)
})

test("account and model failures are summarized independently", () => {
  assert.match(source, /账户异常 \{unhealthyAccountCount\}/)
  assert.match(source, /模型异常 \{unhealthyModelCount\}/)
  assert.match(source, /凭据有效，有告警/)
})
