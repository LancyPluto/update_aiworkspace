import assert from "node:assert/strict"
import { readFile } from "node:fs/promises"
import test from "node:test"
import ts from "typescript"

async function importTsModule(path) {
  const source = await readFile(new URL(path, import.meta.url), "utf8")
  const rewritten = source.replace(
    /import \{ getRequestBaseUrl \} from "@\/api\/client"\r?\n/,
    'function getRequestBaseUrl() { return "http://localhost/" }\n',
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

const resultBlocks = await importTsModule("./taskResultBlocks.ts")

function imageUrls(blocks) {
  assert.equal(blocks[0]?.type, "image")
  return blocks[0].images.map((image) => image.url)
}

test("image results prefer generated output URLs over reference upload URLs", () => {
  const blocks = resultBlocks.buildTaskResultBlocks(
    JSON.stringify({
      prompt: "turn this reference into a poster",
      reference_image: "/generated/uploads/reference.png",
      output_url: "/generated/outputs/42/final.png",
    }),
    { outputModality: "IMAGE", taskNo: "T42" },
  )

  assert.deepEqual(imageUrls(blocks), ["http://localhost/generated/outputs/42/final.png"])
})

test("generated output image URLs infer image modality without task metadata", () => {
  const blocks = resultBlocks.buildTaskResultBlocks(JSON.stringify({
    output_url: "/generated/outputs/43/result.webp",
  }))

  assert.deepEqual(imageUrls(blocks), ["http://localhost/generated/outputs/43/result.webp"])
})

test("explicit images arrays still render every generated image result", () => {
  const blocks = resultBlocks.buildTaskResultBlocks(
    JSON.stringify({
      images: [
        { url: "/generated/images/44/image-1.png" },
        { image_url: "/generated/images/44/image-2.png" },
      ],
    }),
  )

  assert.deepEqual(imageUrls(blocks), [
    "http://localhost/generated/images/44/image-1.png",
    "http://localhost/generated/images/44/image-2.png",
  ])
})

test("legacy image results respect the requested single image count", () => {
  const blocks = resultBlocks.buildTaskResultBlocks(
    JSON.stringify({
      images: [
        { url: "/generated/images/46/image-1.png" },
        { url: "/generated/images/46/image-2.png" },
      ],
    }),
    {
      outputModality: "IMAGE",
      params: { count: 1 },
      taskNo: "T46",
    },
  )

  assert.deepEqual(imageUrls(blocks), ["http://localhost/generated/images/46/image-1.png"])
})

test("top-level imageUrl remains a supported generated image result shape", () => {
  const blocks = resultBlocks.buildTaskResultBlocks(JSON.stringify({
    imageUrl: "/generated/images/45/image-1.png",
  }))

  assert.deepEqual(imageUrls(blocks), ["http://localhost/generated/images/45/image-1.png"])
})

test("upload-only JSON is not rendered as a generated image result", () => {
  const blocks = resultBlocks.buildTaskResultBlocks(JSON.stringify({
    reference_image: "/generated/uploads/reference.png",
  }))

  assert.equal(blocks[0]?.type, "text")
})
