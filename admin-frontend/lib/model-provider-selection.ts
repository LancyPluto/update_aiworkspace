export interface ModelProviderLike {
  code: string
  capabilities: string[]
}

export interface ModelProviderDefaultsLike extends ModelProviderLike {
  defaultModel?: string | null
  defaultBaseUrl?: string | null
  billingDefault?: string | null
}

export interface ModelProviderSwitchState {
  provider: string
  modelName: string
  baseUrl?: string
  capabilities?: string[]
  executionTask?: string
  executionOptionsJson?: string
  endpointPath?: string | null
  billingUnit?: string
}

function normalized(value: string | null | undefined) {
  return (value || "").trim().toLowerCase()
}

export function findModelProvider<T extends ModelProviderLike>(
  providers: T[],
  providerCode: string | null | undefined,
) {
  const code = normalized(providerCode)
  return providers.find((provider) => normalized(provider.code) === code)
}

export function supportedModelProviders<T extends ModelProviderLike>(
  providers: T[],
  supportedProviderCodes: string[] | null | undefined,
) {
  const supported = new Set((supportedProviderCodes || []).map(normalized).filter(Boolean))
  return providers.filter((provider) => supported.has(normalized(provider.code)))
}

export function selectDefaultModelProvider<T extends ModelProviderLike>(
  providers: T[],
  vendorCode: string,
  preferredProvider?: string | null,
) {
  const preferred = findModelProvider(providers, preferredProvider)
  if (preferred) return preferred

  const vendor = normalized(vendorCode)
  return (
    findModelProvider(providers, vendor) ||
    providers.find((provider) => normalized(provider.code).includes(vendor)) ||
    providers[0]
  )
}

export function capabilitiesForModelProvider<T extends ModelProviderLike>(
  capabilities: string[] | null | undefined,
  provider?: T,
) {
  const requested = capabilities || []
  const defaults = provider?.capabilities?.length ? provider.capabilities : ["TEXT_GENERATION"]

  if (requested.length === 0) return [...defaults]
  if (!provider?.capabilities?.length) return [...requested]

  const allowed = new Set(provider.capabilities.map((capability) => capability.toUpperCase()))
  const compatible = requested.filter((capability) => allowed.has(capability.toUpperCase()))
  return compatible.length > 0 ? compatible : [...defaults]
}

export function modelFormAfterProviderSwitch<T extends ModelProviderSwitchState>(
  current: T,
  previous: ModelProviderDefaultsLike | undefined,
  next: ModelProviderDefaultsLike,
  nextCapabilities: string[],
  nextExecutionTask: string,
): T {
  const currentModelName = current.modelName.trim()
  const previousDefaultModel = previous?.defaultModel?.trim() || ""
  const hasCustomModelName = Boolean(currentModelName && currentModelName !== previousDefaultModel)

  return {
    ...current,
    provider: next.code,
    modelName: hasCustomModelName ? current.modelName : next.defaultModel || "",
    baseUrl: next.defaultBaseUrl || "",
    capabilities: [...nextCapabilities],
    executionTask: nextExecutionTask,
    executionOptionsJson: "",
    endpointPath: null,
    billingUnit: next.billingDefault || "TOKEN_PER_M",
  }
}
