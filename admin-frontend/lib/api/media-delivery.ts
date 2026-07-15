import { http } from "./http"

export type MediaDeliveryStatus = {
  provider: string
  publicHost: string
  imageTransformEnabled: boolean
  privateCdnAuthConfigured: boolean
  publicCacheControl: string
  privateCacheControl: string
  legacyCacheControl: string
  policyVersion: string
  issues: string[]
}

export function fetchMediaDeliveryStatus() {
  return http.get<MediaDeliveryStatus>("/api/admin/v1/observability/media-delivery")
}
