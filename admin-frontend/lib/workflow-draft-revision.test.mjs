import assert from "node:assert/strict"
import { readFile } from "node:fs/promises"
import test from "node:test"

const typesSource = await readFile(new URL("./api/types.ts", import.meta.url), "utf8")
const canvasSource = await readFile(
  new URL("../components/admin/workflow/workflow-canvas.tsx", import.meta.url),
  "utf8",
)

test("workflow API types expose immutable publication and draft revision fields", () => {
  for (const field of [
    "draftRevision: number",
    "publishedVersionId: number | null",
    "executionEnabled: boolean",
    "hasUnpublishedChanges: boolean",
    "expectedDraftRevision: number",
  ]) {
    assert.equal(typesSource.includes(field), true, `missing ${field}`)
  }
})

test("workflow canvas sends and refreshes the current draft revision", () => {
  assert.equal(canvasSource.includes("const [draftRevision, setDraftRevision] = useState(0)"), true)
  assert.equal(canvasSource.includes("expectedDraftRevision: draftRevision"), true)
  assert.equal(canvasSource.includes("setDraftRevision(saved.draftRevision)"), true)
  assert.equal(canvasSource.includes("setDraftRevision(workflow.draftRevision)"), true)
})
