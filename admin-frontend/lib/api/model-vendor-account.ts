import { http } from './http'
import type {
  ModelVendorAccount,
  ModelVendorAccountDiscoverModelsResult,
  ModelVendorAccountPayload,
  ModelVendorAccountRoutingPayload,
  ModelVendorAccountTestResult,
} from './types'

const BASE = '/api/admin/v1/model-vendor-accounts'

export function fetchModelVendorAccounts(vendorCode?: string) {
  const query = vendorCode ? `?vendorCode=${encodeURIComponent(vendorCode)}` : ''
  return http.get<ModelVendorAccount[]>(`${BASE}${query}`)
}

export function createModelVendorAccount(payload: ModelVendorAccountPayload) {
  return http.post<ModelVendorAccount>(BASE, payload)
}

export function updateModelVendorAccount(id: number, payload: ModelVendorAccountPayload) {
  return http.put<ModelVendorAccount>(`${BASE}/${id}`, payload)
}

export function updateModelVendorAccountRouting(id: number, payload: ModelVendorAccountRoutingPayload) {
  return http.patch<ModelVendorAccount>(`${BASE}/${id}/routing`, payload)
}

export function deleteModelVendorAccount(id: number) {
  return http.delete<void>(`${BASE}/${id}`)
}

/** 兼容新结构 { success, message, account } 与旧结构直接返回 ModelVendorAccount */
export function normalizeVendorAccountTestResult(
  data: ModelVendorAccountTestResult | ModelVendorAccount | null | undefined,
): ModelVendorAccountTestResult {
  if (!data) {
    return {
      success: false,
      message: '测试接口未返回数据',
      account: {
        id: 0,
        vendorCode: '',
        vendorLabel: '',
        accountName: '',
        balanceQueryMode: 'MANUAL',
        balanceStatus: 'UNKNOWN',
        healthStatus: 'ERROR',
        enabled: false,
        modelCount: 0,
      },
    }
  }
  if (
    typeof data === 'object' &&
    'account' in data &&
    data.account &&
    typeof data.account === 'object' &&
    'id' in data.account
  ) {
    const wrapped = data as ModelVendorAccountTestResult
    const healthStatus = (wrapped.account.healthStatus || '').trim().toUpperCase()
    const credentialValid = healthStatus === 'OK' || healthStatus === 'WARNING'
    return {
      ...wrapped,
      success: Boolean(wrapped.success) || credentialValid,
      message: wrapped.message || wrapped.account.healthMessage || (credentialValid ? '连接成功' : '连接失败'),
    }
  }
  const account = data as ModelVendorAccount
  const healthStatus = (account.healthStatus || '').trim().toUpperCase()
  const success = healthStatus === 'OK' || healthStatus === 'WARNING'
  return {
    success,
    message: account.healthMessage || account.balanceErrorMessage || (success ? '连接成功' : '连接失败'),
    latencyMs: null,
    provider: null,
    modelName: null,
    account,
  }
}

export async function testModelVendorAccount(id: number) {
  const data = await http.post<ModelVendorAccountTestResult | ModelVendorAccount>(`${BASE}/${id}/test`)
  return normalizeVendorAccountTestResult(data)
}

export function refreshModelVendorAccountBalance(id: number) {
  return http.post<ModelVendorAccount>(`${BASE}/${id}/refresh-balance`)
}

export function discoverModelVendorAccountModels(id: number) {
  return http.post<ModelVendorAccountDiscoverModelsResult>(`${BASE}/${id}/discover-models`)
}

export function refreshAllModelVendorAccountBalances() {
  return http.post<{ refreshed: number }>(`${BASE}/refresh-balance-all`)
}
