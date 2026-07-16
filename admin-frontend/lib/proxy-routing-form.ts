import type { ProxyAutoSettings, ProxyRoutingRule } from "./api/proxy-config"

export type RoutingRuleErrors = Partial<Record<"id" | "pattern" | "priority" | "probeUrl", string>>

export function sortRoutingRules(rules: ProxyRoutingRule[]) {
  return [...rules].sort((left, right) => {
    const leftRank = patternRank(left.patternType)
    const rightRank = patternRank(right.patternType)
    return leftRank - rightRank
      || patternBase(right.pattern).length - patternBase(left.pattern).length
      || right.priority - left.priority
      || left.pattern.localeCompare(right.pattern)
      || left.id.localeCompare(right.id)
  })
}

export function routingConfigFingerprint(rules: ProxyRoutingRule[], autoSettings: ProxyAutoSettings) {
  return JSON.stringify({ rules: sortRoutingRules(rules), autoSettings })
}

export function isRoutingConfigDirty(
  rules: ProxyRoutingRule[],
  autoSettings: ProxyAutoSettings,
  savedFingerprint: string,
) {
  return routingConfigFingerprint(rules, autoSettings) !== savedFingerprint
}

export function validateRoutingRule(rule: ProxyRoutingRule): RoutingRuleErrors {
  const errors: RoutingRuleErrors = {}
  if (!/^[A-Za-z0-9_-]{1,40}$/.test(rule.id)) {
    errors.id = "规则 ID 仅支持字母、数字、下划线和连字符"
  }
  const pattern = rule.pattern.trim().toLowerCase()
  if (rule.patternType === "WILDCARD") {
    if (!/^\*\.[a-z0-9-]+(?:\.[a-z0-9-]+)+$/.test(pattern)) {
      errors.pattern = "通配域名必须使用 *.example.com 格式"
    }
  } else if (!/^[a-z0-9-]+(?:\.[a-z0-9-]+)+$/.test(pattern)) {
    errors.pattern = "请输入有效的公网域名"
  }
  if (!Number.isInteger(rule.priority) || rule.priority < 0 || rule.priority > 10_000) {
    errors.priority = "优先级必须在 0 到 10000 之间"
  }
  if (rule.strategy === "AUTO") {
    try {
      const url = new URL(rule.probeUrl)
      if (url.protocol !== "https:" || !url.hostname.includes(".") || url.hostname === "localhost") {
        throw new Error("invalid")
      }
      if (!matchesRuleHost(rule, url.hostname)) {
        errors.probeUrl = "探针域名必须命中当前规则"
      }
    } catch {
      errors.probeUrl = "AUTO 规则必须配置公网 HTTPS 探针地址"
    }
  }
  return errors
}

export function diagnosticDomainForRule(rule: ProxyRoutingRule, explicitTestDomain = "") {
  if (rule.strategy === "AUTO") {
    if (validateRoutingRule(rule).probeUrl) return ""
    try {
      return new URL(rule.probeUrl).hostname.toLowerCase()
    } catch {
      return ""
    }
  }
  const base = patternBase(rule.pattern)
  if (rule.patternType !== "WILDCARD") return base
  const candidate = explicitTestDomain.trim().toLowerCase()
  return matchesRuleHost(rule, candidate) ? candidate : ""
}

export function wildcardTestDomainError(rule: ProxyRoutingRule, explicitTestDomain = "") {
  if (rule.patternType !== "WILDCARD" || rule.strategy === "AUTO") return ""
  const candidate = explicitTestDomain.trim().toLowerCase()
  if (!candidate) return `请输入一个命中 ${rule.pattern} 的真实子域`
  if (!/^[a-z0-9-]+(?:\.[a-z0-9-]+)+$/.test(candidate) || !matchesRuleHost(rule, candidate)) {
    return `测试域名必须命中 ${rule.pattern}`
  }
  return ""
}

export function matchesRuleHost(rule: ProxyRoutingRule, rawHost: string) {
  const host = rawHost.trim().toLowerCase()
  const base = patternBase(rule.pattern)
  if (rule.patternType === "EXACT") return host === base
  if (rule.patternType === "WILDCARD") return host !== base && host.endsWith(`.${base}`)
  return host === base || host.endsWith(`.${base}`)
}

function patternRank(type: ProxyRoutingRule["patternType"]) {
  return type === "EXACT" ? 0 : type === "WILDCARD" ? 1 : 2
}

function patternBase(pattern: string) {
  return pattern.trim().toLowerCase().replace(/^\*\./, "").replace(/^\./, "")
}

export function validateRoutingRules(rules: ProxyRoutingRule[]) {
  const ids = new Set<string>()
  return rules.map((rule) => {
    const errors = validateRoutingRule(rule)
    if (ids.has(rule.id)) errors.id = "规则 ID 不能重复"
    ids.add(rule.id)
    return errors
  })
}
