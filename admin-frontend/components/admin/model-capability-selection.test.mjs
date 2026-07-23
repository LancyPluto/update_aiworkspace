import assert from "node:assert/strict"
import { readFile } from "node:fs/promises"
import test from "node:test"

const source = await readFile(new URL("./unified-api-settings.tsx", import.meta.url), "utf8")

test("unified model editor lets admins change provider before choosing capabilities", () => {
  assert.match(source, /<Label htmlFor="model-provider">供应商协议<\/Label>/)
  assert.match(source, /<Select[\s\S]*?value=\{modelForm\.provider\}[\s\S]*?onValueChange=\{applyModelProvider\}/)
  assert.match(source, /availableModelProviders\.map\(\(provider\) =>/)
})

test("capability column exposes a direct keyboard-accessible configuration action", () => {
  assert.match(source, /aria-label=\{`配置 \$\{model\.displayName \|\| model\.modelName\} 的模型能力`\}/)
  assert.match(source, /onClick=\{\(\) => openEditModel\(model, vendor\.vendorCode\)\}/)
  assert.match(source, /<Settings2 className="h-3\.5 w-3\.5"/)
})

test("every model capability is constrained by the selected provider", () => {
  assert.doesNotMatch(source, /function setModelVisionInput/)
  assert.doesNotMatch(source, /normalized !== VISION_INPUT_CAPABILITY && !allowed\.has/)
  assert.match(source, /return !allowed\.has\(normalized\)/)
})

test("model capability checkboxes have explicit accessible labels", () => {
  assert.match(source, /const capabilityControlId = `model-capability-\$\{capability\.toLowerCase\(\)\}`/)
  assert.match(source, /<Checkbox[\s\S]*?id=\{capabilityControlId\}/)
  assert.match(source, /<Label[\s\S]*?htmlFor=\{capabilityControlId\}/)
})

test("model validation errors stay visible inside the open editor", () => {
  assert.match(source, /<Dialog open=\{modelDialogOpen\}[\s\S]*?<Alert variant="destructive" role="alert">/)
  assert.match(source, /<AlertTitle>无法保存模型<\/AlertTitle>/)
})

test("selected capabilities retain readable text in light and dark themes", () => {
  assert.match(source, /border-blue-500 bg-blue-500\/10 text-foreground/)
  assert.doesNotMatch(source, /border-blue-500 bg-blue-500\/10 text-blue-100/)
})

test("model editor persists non-secret request and response contracts without masking", () => {
  assert.match(source, /requestSchemaJson:\s*model\.requestSchemaJson \|\| "\{\}"/)
  assert.match(source, /requestMappingJson:\s*model\.requestMappingJson \|\| "\{\}"/)
  assert.match(source, /responseMappingJson:\s*model\.responseMappingJson \|\| "\{\}"/)
  assert.match(source, /value=\{modelForm\.requestSchemaJson \|\| ""\}/)
  assert.match(source, /jsonObjectValidationError\(modelForm\.responseMappingJson/)
  assert.doesNotMatch(source, /requestSchemaJsonMasked|requestMappingJsonMasked|responseMappingJsonMasked/)
})

test("model contract status distinguishes ready models from documentation-pending models", () => {
  assert.match(source, /contractStatus:\s*"DOCS_PENDING"/)
  assert.match(source, /<SelectItem value="DOCS_PENDING">文档待补<\/SelectItem>/)
  assert.match(source, /<SelectItem value="READY">契约就绪<\/SelectItem>/)
  assert.match(source, /apiContractVersion:\s*model\.apiContractVersion \|\| ""/)
  assert.match(source, /contractVerifiedAt:\s*toDateTimeLocalValue\(model\.contractVerifiedAt\)/)
})
