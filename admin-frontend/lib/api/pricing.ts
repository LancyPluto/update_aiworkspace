import { http } from './http'

export interface PricingMargin {
  id?: number
  scopeType: string
  scopeRef?: number | null
  markupRatio: number
  minCredits: number
  imageEstimateInputTokens?: number | null
  imageEstimateOutputTokens?: number | null
  enabled: boolean
  remark?: string | null
}

export interface PricingRule {
  id?: number
  scopeType: string
  scopeRef?: number | null
  paramKey: string
  ruleType: string
  matchOp: string
  matchValue?: string | null
  factor: number
  extraCredits: number
  priority: number
  enabled: boolean
  remark?: string | null
}

export function fetchPricingMargins() {
  return http.get<PricingMargin[]>('/api/admin/v1/pricing/margins')
}

export function savePricingMargin(body: PricingMargin) {
  return http.post<PricingMargin>('/api/admin/v1/pricing/margins', body)
}

export function deletePricingMargin(id: number) {
  return http.delete<void>(`/api/admin/v1/pricing/margins/${id}`)
}

export function fetchPricingRules() {
  return http.get<PricingRule[]>('/api/admin/v1/pricing/rules')
}

export function savePricingRule(body: PricingRule) {
  return http.post<PricingRule>('/api/admin/v1/pricing/rules', body)
}

export function deletePricingRule(id: number) {
  return http.delete<void>(`/api/admin/v1/pricing/rules/${id}`)
}
