import assert from "node:assert/strict"
import { readFile } from "node:fs/promises"
import test from "node:test"
import ts from "typescript"

async function importTsModule(path) {
  const source = await readFile(new URL(path, import.meta.url), "utf8")
  const { outputText } = ts.transpileModule(source, {
    compilerOptions: {
      module: ts.ModuleKind.ESNext,
      target: ts.ScriptTarget.ES2022,
      moduleResolution: ts.ModuleResolutionKind.Bundler,
      importsNotUsedAsValues: ts.ImportsNotUsedAsValues.Remove,
    },
  })
  return import(`data:text/javascript;base64,${Buffer.from(outputText).toString("base64")}`)
}

const encoding = await importTsModule("./displayEncoding.ts")

test("repairs UTF-8 text that was decoded as Windows-1252", () => {
  assert.equal(encoding.repairMojibakeText("AI PPT ç”Ÿæˆå™¨"), "AI PPT 生成器")
  assert.equal(encoding.repairMojibakeText("åŸºäºŽ banana-slides çš„å¤šæ­¥éª¤ PPT ç”Ÿæˆ"), "基于 banana-slides 的多步骤 PPT 生成")
})

test("leaves normal display text unchanged", () => {
  assert.equal(encoding.repairMojibakeText("AI 漫剧生成智能体"), "AI 漫剧生成智能体")
  assert.equal(encoding.repairMojibakeText("Text to Video"), "Text to Video")
  assert.equal(encoding.repairMojibakeText("720p · 16:9"), "720p · 16:9")
})
