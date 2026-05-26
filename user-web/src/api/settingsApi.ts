import { apiRequest } from "./client"

export interface CustomerServiceSettings {
  enabled: boolean
  title: string
  description: string
  qrCodeUrl: string
}

export async function fetchCustomerServiceSettings(options?: {
  token?: string | null
}): Promise<CustomerServiceSettings> {
  return apiRequest<CustomerServiceSettings>("GET", "/api/v1/settings/customer-service", {
    token: options?.token,
  })
}
