import assert from "node:assert/strict"
import { readFile } from "node:fs/promises"
import test from "node:test"
import ts from "typescript"

async function importTsModule(path) {
  const source = await readFile(new URL(path, import.meta.url), "utf8")
  const rewritten = source.replace(
    /import \{ getApiOrigin \} from "@\/api\/client"\r?\n/,
    'function getApiOrigin() { return "" }\n',
  )
  const { outputText } = ts.transpileModule(rewritten, {
    compilerOptions: {
      module: ts.ModuleKind.ESNext,
      target: ts.ScriptTarget.ES2022,
      moduleResolution: ts.ModuleResolutionKind.Bundler,
      importsNotUsedAsValues: ts.ImportsNotUsedAsValues.Remove,
    },
  })
  return import(`data:text/javascript;base64,${Buffer.from(outputText).toString("base64")}`)
}

const media = await importTsModule("./toolCoverMedia.ts")

test("normalizeMediaUrl rewrites docker backend asset URLs to browser paths", () => {
  assert.equal(
    media.normalizeMediaUrl("http://backend:8080/generated/uploads/20260611/demo.png"),
    "/generated/uploads/20260611/demo.png",
  )
})

test("normalizeMediaUrl keeps external CDN URLs unchanged", () => {
  const url = "https://cdn.example.com/assets/demo.png"
  assert.equal(media.normalizeMediaUrl(url), url)
})
