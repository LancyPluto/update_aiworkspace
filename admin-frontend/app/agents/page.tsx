"use client"

import { Bot } from "lucide-react"
import { AdminLayout } from "@/components/admin/admin-layout"
import { AdminHeader } from "@/components/admin/header"

export default function AgentsPage() {
  return (
    <AdminLayout>
      <AdminHeader title="智能体管理" description="智能体管理能力即将上线，敬请期待" />

      <div className="flex flex-col items-center justify-center px-6 py-24 text-center">
        <div className="mb-4 flex h-16 w-16 items-center justify-center rounded-2xl bg-primary/10">
          <Bot className="h-8 w-8 text-primary" />
        </div>
        <h2 className="text-lg font-semibold">智能体管理</h2>
        <p className="mt-2 max-w-md text-sm text-muted-foreground">
          我们正在筹备智能体相关管理能力，后续将在此提供智能体配置与运营入口。
        </p>
      </div>
    </AdminLayout>
  )
}
