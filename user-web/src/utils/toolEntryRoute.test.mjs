import assert from "node:assert/strict"
import { readFile } from "node:fs/promises"
import { stripTypeScriptTypes } from "node:module"
import test from "node:test"

async function loadRoutes() {
  const source = await readFile(new URL("./toolEntryRoute.ts", import.meta.url), "utf8")
  const outputText = stripTypeScriptTypes(source.replace(/import\s+type[^\\n]+\\n/, ""), { mode: "transform" })
  return import(`data:text/javascript;base64,${Buffer.from(outputText).toString("base64")}`)
}

test("PPT workspace tools route to the workbench while ordinary tools keep chat", async () => {
  const { toolEntryRoute } = await loadRoutes()
  assert.deepEqual(toolEntryRoute("banana_ppt_generator"), { path: "/ppt" })
  assert.deepEqual(toolEntryRoute("another-ppt", "ppt_workspace"), { path: "/ppt" })
  assert.deepEqual(toolEntryRoute("image-tool", "CHAT"), { path: "/chat/image-tool" })
})
