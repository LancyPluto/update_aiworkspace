import assert from "node:assert/strict"
import { readFile } from "node:fs/promises"
import test from "node:test"

const modelConfigFiles = [
  "unified-api-settings.tsx",
  "agent-model-settings.tsx",
].map((name) => new URL(`./${name}`, import.meta.url))

test("model configuration copy uses frontend/user-facing wording instead of Agent optional wording", async () => {
  const combined = (await Promise.all(modelConfigFiles.map((file) => readFile(file, "utf8")))).join("\n")

  assert.doesNotMatch(combined, /Agent 可选|作为 Agent 模型|用户端 Agent 页/)
  assert.match(combined, /前台可选|用户端可选/)
})
