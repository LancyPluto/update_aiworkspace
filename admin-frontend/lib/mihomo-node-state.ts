import type { MihomoNodeItem, MihomoNodeTestResult } from "./api/proxy-config"

export function mergeMihomoNodeTest(
  nodes: MihomoNodeItem[],
  result: MihomoNodeTestResult,
) {
  if (!result.available || result.latencyMs <= 0) return nodes

  return nodes.map((node) => node.name === result.nodeName
    ? { ...node, available: true, latencyMs: result.latencyMs }
    : node)
}
