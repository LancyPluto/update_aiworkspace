import assert from "node:assert/strict"
import test from "node:test"

import { memoryEventTitle } from "./runTimelineEvents.ts"

test("memory event title distinguishes empty, failed, and skipped retrieval", () => {
  assert.equal(memoryEventTitle({ count: 0 }), "本次无相关长期记忆")
  assert.equal(memoryEventTitle({ count: 0, error: "retrieve_failed" }), "长期记忆读取失败")
  assert.equal(memoryEventTitle({ count: 0, memoryInjectionSkipped: true }), "已跳过工具记忆注入")
  assert.equal(memoryEventTitle({ count: 2 }), "已读取 2 条长期记忆")
})
