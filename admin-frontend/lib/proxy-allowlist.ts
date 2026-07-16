import type { ProxyRoutingRule } from "./api/proxy-config"

export function normalizeProxyDomain(rawValue: string) {
  const raw = rawValue.trim().toLowerCase().replace(/^\*\./, "").replace(/^\./, "")
  if (!raw) return ""
  try {
    const url = raw.includes("://") ? new URL(raw) : new URL(`https://${raw}`)
    const domain = url.hostname.toLowerCase().replace(/^\*\./, "").replace(/^\./, "")
    if (!domain.includes(".") || domain === "localhost" || /^\d+(?:\.\d+){3}$/.test(domain)) return ""
    return domain
  } catch {
    return ""
  }
}

export function proxyDomainsFromRules(rules: ProxyRoutingRule[]) {
  return compactProxyDomains(rules
    .filter((rule) => rule.enabled && rule.strategy !== "DIRECT")
    .map((rule) => normalizeProxyDomain(rule.pattern)))
}

export function proxyDomainsToRules(domains: string[]): ProxyRoutingRule[] {
  return compactProxyDomains(domains).map((domain, index) => ({
    id: `site-${domainHash(domain)}`,
    patternType: "SUFFIX",
    pattern: domain,
    strategy: "PROXY",
    priority: 1000 - index,
    enabled: true,
    note: "",
    probeUrl: "",
  }))
}

export function addProxyDomain(domains: string[], rawDomain: string) {
  const domain = normalizeProxyDomain(rawDomain)
  if (!domain) return compactProxyDomains(domains)
  return compactProxyDomains([...domains, domain])
}

export function compactProxyDomains(domains: string[]) {
  const normalized = [...new Set(domains.map(normalizeProxyDomain).filter(Boolean))]
    .sort((left, right) => left.length - right.length || left.localeCompare(right))
  const compacted = normalized.filter((domain, index) => !normalized.some((candidate, candidateIndex) =>
    candidateIndex < index && (domain === candidate || domain.endsWith(`.${candidate}`))))
  return compacted.sort((left, right) => left.localeCompare(right))
}

function domainHash(domain: string) {
  let hash = 2166136261
  for (let index = 0; index < domain.length; index += 1) {
    hash ^= domain.charCodeAt(index)
    hash = Math.imul(hash, 16777619)
  }
  return (hash >>> 0).toString(36)
}
