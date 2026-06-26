import assert from "node:assert/strict"
import { readFile } from "node:fs/promises"
import test from "node:test"

const source = await readFile(new URL("./imageCompressor.ts", import.meta.url), "utf8")

test("image compressor exposes the expected browser canvas pipeline", () => {
  assert.match(source, /export async function compressImage\(file: File, maxDimension = 512, quality = 0\.8\): Promise<File>/)
  assert.match(source, /if \(!isImageFile\(file\)\) return file/)
  assert.match(source, /const maxSide = Math\.max\(width, height\)/)
  assert.match(source, /const scale = maxDimension \/ maxSide/)
  assert.match(source, /createImageBitmap/)
  assert.match(source, /document\.createElement\("canvas"\)/)
  assert.match(source, /canvas\.toBlob/)
  assert.match(source, /"image\/webp"/)
  assert.match(source, /"image\/jpeg"/)
  assert.match(source, /new File\(\[blob\], file\.name/)
})
