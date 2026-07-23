import assert from "node:assert/strict"
import { readFile } from "node:fs/promises"
import test from "node:test"

const source = await readFile(new URL("./page.tsx", import.meta.url), "utf8")

test("tool form persists an explicit multi-capability requirement", () => {
  assert.match(source, /requiredModelCapabilities:\s*string\[\]/)
  assert.match(source, /requiredModelCapabilities,\s*\n/)
  assert.match(source, /toggleRequiredModelCapability/)
  assert.match(source, />所需模型能力</)
})

test("tool capability checkboxes have explicit accessible labels", () => {
  assert.match(source, /const capabilityControlId = `required-model-capability-\$\{capability\.toLowerCase\(\)\}`/)
  assert.match(source, /<Checkbox[\s\S]*?id=\{capabilityControlId\}/)
  assert.match(source, /<Label[\s\S]*?htmlFor=\{capabilityControlId\}/)
})

test("tool model choices use all selected capabilities and report invalidated selections", () => {
  assert.match(source, /modelConfigSupportsToolRequirements\(\s*c,\s*requiredModelCapabilities,/)
  assert.match(source, /requiredExecutionHandler/)
  assert.match(source, /setModelSelectionNotice\(/)
  assert.match(source, /已清除不匹配的模型/)
})

test("a tool submits an ordered model list and a selected default model", () => {
  assert.match(source, /modelConfigIds:\s*string\[\]/)
  assert.match(source, /if \(form\.modelConfigIds\.length === 0\)/)
  assert.match(source, /modelConfigIds:\s*form\.modelConfigIds\.map\(Number\)/)
  assert.match(source, /defaultModelConfigId:\s*Number\(form\.defaultModelConfigId\)/)
  assert.match(source, /请从已绑定模型中指定一个默认模型/)
})

test("models with pending API documentation cannot be newly bound", () => {
  assert.match(source, /function isModelContractReady/)
  assert.match(source, /const disabled = !ready && !checked/)
  assert.match(source, />文档待补</)
  assert.match(source, /暂不能新增绑定/)
  assert.match(source, /<RadioGroup[\s\S]*?value=\{form\.defaultModelConfigId\}/)
})

test("digital human remains an execution handler rather than a selectable model capability", () => {
  assert.doesNotMatch(source, /DIGITAL_HUMAN:\s*"数字人"/)
  assert.match(source, /defaultRequiredModelCapabilitiesForTool/)
  assert.match(source, /normalizeLegacyRequiredModelCapabilities\(tool\.requiredModelCapabilities\)/)
})

test("changing tool type clears stale templates and submits the matching execution handler", () => {
  assert.match(source, /const templateCode = templateCodeByToolType\[value\] \|\| ""/)
  assert.match(source, /templateCode,\s*\n\s*requiredModelCapabilities:/)
  assert.match(source, /return defaultExecutionHandlerForToolType\(form\.toolType\)/)
  assert.match(source, /executionHandler: requiredExecutionHandler/)
  assert.doesNotMatch(source, /executionHandler: editingTool\?\.executionHandler \|\| undefined/)
})

test("compact tool fields become two columns only at the small breakpoint", () => {
  assert.doesNotMatch(source, /className="grid grid-cols-2 gap-4"/)
})
