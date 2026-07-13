import { readdir, stat } from "node:fs/promises"
import path from "node:path"

const MAX_CHUNK_BYTES = 500_000
const assetsDir = new URL("../dist/assets/", import.meta.url)
const files = (await readdir(assetsDir)).filter((file) => file.endsWith(".js"))
const chunks = await Promise.all(files.map(async (file) => ({
  file,
  bytes: (await stat(new URL(file, assetsDir))).size,
})))
const largest = chunks.sort((a, b) => b.bytes - a.bytes)[0]

if (!largest) throw new Error("No JavaScript chunks found in dist/assets")

console.log(`Largest JavaScript chunk: ${largest.file} (${(largest.bytes / 1000).toFixed(2)} kB)`)
if (largest.bytes > MAX_CHUNK_BYTES) {
  throw new Error(`Bundle size limit exceeded: ${largest.bytes} > ${MAX_CHUNK_BYTES} bytes`)
}
