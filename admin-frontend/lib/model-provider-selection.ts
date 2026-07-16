export interface ModelProviderLike {
  code: string
  capabilities: string[]
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
  agentOnlyCapability = "VISION_INPUT",
) {
  const requested = capabilities || []
  const agentOnly = requested.filter((capability) => capability.toUpperCase() === agentOnlyCapability)
  const defaults = provider?.capabilities?.length ? provider.capabilities : ["TEXT_GENERATION"]

  if (requested.length === 0) return [...defaults]
  if (!provider?.capabilities?.length) return [...requested]

  const allowed = new Set(provider.capabilities.map((capability) => capability.toUpperCase()))
  const compatible = requested.filter((capability) => allowed.has(capability.toUpperCase()))
  const executable = compatible.length > 0 ? compatible : [...defaults]
  return [
    ...executable,
    ...agentOnly.filter(
      (capability) => !executable.some((item) => item.toUpperCase() === capability.toUpperCase()),
    ),
  ]
}
