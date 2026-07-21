export interface ModelRoutingAccount {
  id: number
  enabled: boolean
  loadBalanceEnabled?: boolean | null
  routingPoolId?: number | null
  routingPoolName?: string | null
}

export interface ModelRoutingPool<T extends ModelRoutingAccount = ModelRoutingAccount> {
  id: number
  name: string
  accounts: T[]
  eligibleAccounts: T[]
}

export interface ModelRoutingTarget {
  vendorAccountId: number
  routingPoolId: number | null
}

export function buildModelRoutingPools<T extends ModelRoutingAccount>(accounts: T[]): ModelRoutingPool<T>[] {
  const pools = new Map<number, ModelRoutingPool<T>>()

  for (const account of accounts) {
    if (account.routingPoolId == null) continue
    const name = account.routingPoolName?.trim() || `池 #${account.routingPoolId}`
    const existing = pools.get(account.routingPoolId)
    if (existing) {
      existing.accounts.push(account)
      if (account.enabled && account.loadBalanceEnabled === true) existing.eligibleAccounts.push(account)
      continue
    }
    pools.set(account.routingPoolId, {
      id: account.routingPoolId,
      name,
      accounts: [account],
      eligibleAccounts: account.enabled && account.loadBalanceEnabled === true ? [account] : [],
    })
  }

  return [...pools.values()].sort((left, right) =>
    left.name.localeCompare(right.name, "zh-CN") || left.id - right.id,
  )
}

export function resolveModelRoutingTarget<T extends ModelRoutingAccount>(
  value: string,
  currentVendorAccountId: number | null | undefined,
  accounts: T[],
): ModelRoutingTarget | null {
  const [kind, rawId] = value.split(":", 2)
  const id = Number(rawId)
  if (!Number.isSafeInteger(id) || id <= 0) return null

  if (kind === "account") {
    const account = accounts.find((candidate) => candidate.id === id)
    if (!account) return null
    return {
      vendorAccountId: account.id,
      routingPoolId: null,
    }
  }

  if (kind !== "pool") return null
  const pool = buildModelRoutingPools(accounts).find((candidate) => candidate.id === id)
  if (!pool || pool.eligibleAccounts.length === 0) return null
  const currentAnchor = pool.eligibleAccounts.find((account) => account.id === currentVendorAccountId)
  return {
    vendorAccountId: currentAnchor?.id ?? pool.eligibleAccounts[0].id,
    routingPoolId: pool.id,
  }
}
