import assert from "node:assert/strict"
import { readFile } from "node:fs/promises"
import test from "node:test"

test("agent model form preserves vendor account id for inherited credentials", async () => {
  const source = await readFile(new URL("./agent-model-settings.tsx", import.meta.url), "utf8")

  assert.match(source, /interface ModelForm[\s\S]*vendorAccountId:\s*number \| null/)
  assert.match(source, /vendorAccountId:\s*config\.vendorAccountId \?\? null/)
  assert.match(source, /vendorAccountId:\s*form\.vendorAccountId \?\? undefined/)
})

test("legacy digital-human capability is not treated as a model modality", async () => {
  const source = await readFile(new URL("./agent-model-settings.tsx", import.meta.url), "utf8")

  assert.doesNotMatch(source, /capability\.includes\("DIGITAL_HUMAN"\)/)
})

test("model enablement is implicit while frontend selection remains independent", async () => {
  const source = await readFile(new URL("./agent-model-settings.tsx", import.meta.url), "utf8")

  assert.match(source, /function toPayload[\s\S]*enabled:\s*true/)
  assert.doesNotMatch(source, /toggleConfigEnabled|togglingEnabledId|model-enabled-|form\.enabled|config\.enabled === false/)
})
