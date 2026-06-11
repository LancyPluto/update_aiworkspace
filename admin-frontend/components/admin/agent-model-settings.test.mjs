import assert from "node:assert/strict"
import { readFile } from "node:fs/promises"
import test from "node:test"

test("agent model form preserves vendor account id for inherited credentials", async () => {
  const source = await readFile(new URL("./agent-model-settings.tsx", import.meta.url), "utf8")

  assert.match(source, /interface ModelForm[\s\S]*vendorAccountId:\s*number \| null/)
  assert.match(source, /vendorAccountId:\s*config\.vendorAccountId \?\? null/)
  assert.match(source, /vendorAccountId:\s*form\.vendorAccountId \?\? undefined/)
})
