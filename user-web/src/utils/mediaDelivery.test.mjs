import assert from "node:assert/strict"
import { readFile } from "node:fs/promises"
import test from "node:test"
const ts = await import(process.env.TYPESCRIPT_MODULE || "typescript")

async function importMediaDelivery() {
  const source = await readFile(new URL("./mediaDelivery.ts", import.meta.url), "utf8")
  const rewritten = source
    .replace(
      /import \{ getApiOrigin \} from "@\/api\/client"\r?\n/,
      'function getApiOrigin() { return "" }\n',
    )
    .replace(/import\.meta\.env/g, "({ PROD: false })")
  const { outputText } = ts.transpileModule(rewritten, {
    compilerOptions: {
      module: ts.ModuleKind.ESNext,
      target: ts.ScriptTarget.ES2022,
      moduleResolution: ts.ModuleResolutionKind.Bundler,
    },
  })
  return import(`data:text/javascript;base64,${Buffer.from(outputText).toString("base64")}`)
}

const media = await importMediaDelivery()

test("builds OSS image variants while preserving query and hash", () => {
  assert.equal(
    media.buildOssImageVariant("https://demo.oss-cn.test/file.jpg?style=old#hero", "card"),
    "https://demo.oss-cn.test/file.jpg?style=old&x-oss-process=image%2Fresize%2Cw_640%2Fformat%2Cwebp%2Fquality%2Cq_85#hero",
  )
  assert.equal(
    media.buildOssImageVariant("https://demo.oss-cn.test/file.jpg?x-oss-process=old", "list"),
    "https://demo.oss-cn.test/file.jpg?x-oss-process=image%2Fresize%2Cw_320%2Fformat%2Cwebp%2Fquality%2Cq_80",
  )
})

test("does not transform signed or unmanaged image URLs", () => {
  assert.deepEqual(
    media.buildImageCandidateChain("https://demo.oss-cn.test/file.jpg?Signature=secret", "card", true),
    ["https://demo.oss-cn.test/file.jpg?Signature=secret"],
  )
  assert.deepEqual(
    media.buildImageCandidateChain("https://example.com/file.jpg", "card", true),
    ["https://example.com/file.jpg"],
  )
})

test("video preview preserves the complete query and hash", () => {
  assert.deepEqual(
    media.buildVideoCandidateChain("https://demo.oss-cn.test/movie.mp4?style=public#clip", true),
    [
      "https://demo.oss-cn.test/movie.preview-480p.mp4?style=public#clip",
      "https://demo.oss-cn.test/movie.mp4?style=public#clip",
    ],
  )
})

test("video poster is disabled for signed URLs", () => {
  assert.equal(
    media.buildVideoPosterUrl("https://demo.oss-cn.test/movie.mp4?auth_key=secret", true),
    "",
  )
})
