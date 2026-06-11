import assert from "node:assert/strict"
import { readFile } from "node:fs/promises"
import test from "node:test"
import ts from "typescript"

async function importTsModule(path) {
  let source = await readFile(new URL(path, import.meta.url), "utf8")
  source = source
    .replace(
      /import type \{ CommunityPost, TaskDetail \} from "@\/api\/types"\r?\n/,
      "",
    )
    .replace(
      /import type \{ AssetPreviewItem \} from "@\/types\/assetPreview"\r?\n/,
      "",
    )
    .replace(
      /import type \{ ResultBlock \} from "@\/types\/result"\r?\n/,
      "",
    )
    .replace(
      /import \{ communityDisplaySubtitle, communityDisplayTitle \} from "@\/utils\/communityDisplay"\r?\n/,
      "function communityDisplayTitle(input) { return input.title || input.prompt || 'asset' }\nfunction communityDisplaySubtitle(input) { return input.subtitle || input.description || '' }\n",
    )
    .replace(
      /import \{ resolveCommunityAudioMedia \} from "@\/utils\/communityAudioMedia"\r?\n/,
      "function resolveCommunityAudioMedia() { return null }\n",
    )
    .replace(
      /import \{ resolveCommunityAuthorName, resolveCommunityAuthorAvatar, resolveCommunityPrompt \} from "@\/utils\/communityPostNormalize"\r?\n/,
      "function resolveCommunityAuthorName(post) { return post.authorNickname || '' }\nfunction resolveCommunityAuthorAvatar(post) { return post.authorAvatarUrl || null }\nfunction resolveCommunityPrompt(post) { return post.promptPreview || (post.promptVisible ? post.prompt : '') || '' }\n",
    )
    .replace(
      /import \{ buildTaskResultBlocks, resolveAudioTracks \} from "@\/utils\/taskResultBlocks"\r?\n/,
      "function buildTaskResultBlocks() { return [] }\nfunction resolveAudioTracks() { return [] }\n",
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

const adapter = await importTsModule("./assetPreviewAdapter.ts")

test("private task assets preserve published community post state from task detail", () => {
  const asset = adapter.assetFromTask(
    {
      taskId: 42,
      taskNo: "T2026060101",
      userId: 7,
      status: "SUCCESS",
      toolCode: "image_tool",
      toolName: "Image Tool",
      outputModality: "IMAGE",
      params: { prompt: "make it vivid" },
      result: { resourceType: "IMAGE", contentText: "" },
      createdAt: "2026-06-01T10:00:00",
      communityPostId: 99,
      communityPromptVisible: true,
    },
    {
      blocks: [{
        id: "image-1",
        type: "image",
        title: "Final image",
        images: [{ url: "/generated/outputs/42/final.png" }],
      }],
    },
  )

  assert.equal(asset.communityPostId, 99)
  assert.equal(asset.promptVisible, true)
})
