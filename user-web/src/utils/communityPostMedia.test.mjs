import assert from "node:assert/strict"
import { readFile } from "node:fs/promises"
import test from "node:test"
const ts = await import(process.env.TYPESCRIPT_MODULE || "typescript")

async function importTsModule(path) {
  const source = await readFile(new URL(path, import.meta.url), "utf8")
  const rewritten = source
    .replace(
      /import \{ getApiOrigin \} from "@\/api\/client"\r?\n/,
      'function getApiOrigin() { return "" }\n',
    )
    .replace(
      /import \{ buildTaskResultBlocks \} from "@\/utils\/taskResultBlocks"\r?\n/,
      "function buildTaskResultBlocks() { return [] }\n",
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

const media = await importTsModule("./communityPostMedia.ts")

test("resolves community image derivative URLs without using originals", () => {
  assert.equal(
    media.resolveCommunityDerivativeUrl("/generated/demo/photo.png", "image-thumb"),
    "/generated/demo/photo.thumb-640.webp",
  )
  assert.equal(
    media.resolveCommunityDerivativeUrl("/generated/demo/photo.png?token=abc", "image-lqip"),
    "/generated/demo/photo.lqip-32.webp?token=abc",
  )
})

test("resolves community video poster and low-bandwidth preview URLs", () => {
  assert.equal(
    media.resolveCommunityDerivativeUrl("https://cdn.example.com/video/work.webm#clip", "video-poster"),
    "https://cdn.example.com/video/work.poster-640.webp#clip",
  )
  assert.equal(
    media.resolveCommunityDerivativeUrl("/generated/video/work.mp4", "video-preview"),
    "/generated/video/work.preview-480p.mp4",
  )
})

test("does not derive data URLs", () => {
  assert.equal(media.resolveCommunityDerivativeUrl("data:image/png;base64,abc", "image-thumb"), "")
})
