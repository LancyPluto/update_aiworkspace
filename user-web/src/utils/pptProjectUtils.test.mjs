import assert from "node:assert/strict"
import { readFile } from "node:fs/promises"
import test from "node:test"
import ts from "typescript"

async function importTsModule(path) {
  let source = await readFile(new URL(path, import.meta.url), "utf8")
  source = source
    .replace(/import type \{ PptPage, PptPageOutline, PptProjectDetail \} from "@\/api\/pptApi"\r?\n/, "")
    .replace(
      /import \{ getRequestBaseUrl \} from "@\/api\/client"\r?\n/,
      'function getRequestBaseUrl() { return "https://app.example" }\n',
    )
    .replace(
      /import \{ getSessionBearerJwt \} from "@\/api\/sessionBearer"\r?\n/,
      "function getSessionBearerJwt() { return null }\n",
    )

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

const utils = await importTsModule("./pptProjectUtils.ts")

test("PPT download URLs rewrite absolute engine file paths through the BFF proxy", () => {
  assert.equal(
    utils.resolvePptDownloadUrl("https://engine.example/files/project-1/exports/demo.pptx", 7),
    "https://app.example/api/v1/ppt/files/7/project-1/exports/demo.pptx",
  )
})

test("PPT download URLs keep already proxied file paths on the app origin", () => {
  assert.equal(
    utils.resolvePptDownloadUrl("/api/v1/ppt/files/7/project-1/exports/demo.pdf", 7),
    "https://app.example/api/v1/ppt/files/7/project-1/exports/demo.pdf",
  )
})

test("PPT download URL proxy detection distinguishes app BFF URLs from external URLs", () => {
  assert.equal(
    utils.isPptBffDownloadUrl("https://app.example/api/v1/ppt/files/7/project-1/exports/demo.pdf"),
    true,
  )
  assert.equal(
    utils.isPptBffDownloadUrl("https://cdn.example/export.pptx"),
    false,
  )
})
