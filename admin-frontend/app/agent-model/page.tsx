"use client"

import { AdminLayout } from "@/components/admin/admin-layout"
import { AgentModelSettings } from "@/components/admin/agent-model-settings"
import { AdminHeader } from "@/components/admin/header"

export default function AgentModelPage() {
  return (
    <AdminLayout>
      <AdminHeader title="大模型接入" description="在系统配置中统一维护 Agent 使用的大模型连接参数。" />

      <div className="p-6">
        <AgentModelSettings />
      </div>
    </AdminLayout>
  )
}
