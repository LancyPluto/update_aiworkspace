import assert from "node:assert/strict"
import test from "node:test"
import { createCreditRealtime } from "./creditRealtime.ts"

class FakeTarget extends EventTarget {
  visibilityState = "visible"
}

class FakeEventSource extends EventTarget {
  closed = false

  close() {
    this.closed = true
  }
}

function createHarness() {
  const windowTarget = new FakeTarget()
  const documentTarget = new FakeTarget()
  const sources = []
  const timeouts = new Map()
  const intervals = new Map()
  const refreshReasons = []
  let nextTimerId = 1

  const realtime = createCreditRealtime({
    url: "https://example.test/api/v1/credits/events",
    refresh: async reason => refreshReasons.push(reason),
    createEventSource: (url, options) => {
      const source = new FakeEventSource()
      source.url = url
      source.options = options
      sources.push(source)
      return source
    },
    windowTarget,
    documentTarget,
    setTimeoutFn: callback => {
      const id = nextTimerId++
      timeouts.set(id, callback)
      return id
    },
    clearTimeoutFn: id => timeouts.delete(id),
    setIntervalFn: callback => {
      const id = nextTimerId++
      intervals.set(id, callback)
      return id
    },
    clearIntervalFn: id => intervals.delete(id),
  })

  return { realtime, windowTarget, documentTarget, sources, timeouts, intervals, refreshReasons }
}

test("starts one credentialed event stream and closes it on stop", () => {
  const harness = createHarness()

  harness.realtime.start()
  harness.realtime.start()

  assert.equal(harness.sources.length, 1)
  assert.equal(harness.sources[0].url, "https://example.test/api/v1/credits/events")
  assert.deepEqual(harness.sources[0].options, { withCredentials: true })
  assert.equal(harness.intervals.size, 1)

  harness.realtime.stop()

  assert.equal(harness.sources[0].closed, true)
  assert.equal(harness.intervals.size, 0)
})

test("debounces repeated credit events into one authoritative refresh", async () => {
  const harness = createHarness()
  harness.realtime.start()
  const source = harness.sources[0]

  source.dispatchEvent(new Event("credit-account-changed"))
  source.dispatchEvent(new Event("credit-account-changed"))

  assert.equal(harness.timeouts.size, 1)
  const callback = [...harness.timeouts.values()][0]
  callback()
  await Promise.resolve()

  assert.deepEqual(harness.refreshReasons, ["event"])
})

test("refreshes on stream open network recovery and visible-page recovery", async () => {
  const harness = createHarness()
  harness.realtime.start()

  harness.sources[0].dispatchEvent(new Event("open"))
  harness.windowTarget.dispatchEvent(new Event("online"))
  harness.documentTarget.visibilityState = "hidden"
  harness.documentTarget.dispatchEvent(new Event("visibilitychange"))
  assert.equal(harness.intervals.size, 0)
  harness.documentTarget.visibilityState = "visible"
  harness.documentTarget.dispatchEvent(new Event("visibilitychange"))
  await Promise.resolve()

  assert.deepEqual(harness.refreshReasons, ["open", "online", "visible"])
  assert.equal(harness.intervals.size, 1)
})

test("visible-page polling refreshes and stop removes global listeners", async () => {
  const harness = createHarness()
  harness.realtime.start()
  const poll = [...harness.intervals.values()][0]

  poll()
  harness.realtime.stop()
  harness.windowTarget.dispatchEvent(new Event("online"))
  harness.documentTarget.dispatchEvent(new Event("visibilitychange"))
  await Promise.resolve()

  assert.deepEqual(harness.refreshReasons, ["poll"])
})
