"use client"

import Link from "next/link"
import { usePathname } from "next/navigation"
import { cn } from "@/lib/utils"
import {
  Coins,
  FileText,
  FolderTree,
  LayoutDashboard,
  ListTodo,
  ReceiptText,
  Settings,
  Sparkles,
  Users,
  Wrench,
} from "lucide-react"

const navigation = [
  { name: "数据概览", href: "/", icon: LayoutDashboard },
  { name: "用户管理", href: "/users", icon: Users },
  { name: "AI 工具管理", href: "/tools", icon: Wrench },
  { name: "分类管理", href: "/categories", icon: FolderTree },
  { name: "Prompt 管理", href: "/prompts", icon: FileText },
  { name: "任务管理", href: "/tasks", icon: ListTodo },
  { name: "计费日志", href: "/billing", icon: ReceiptText },
  { name: "会员算力", href: "/credits", icon: Coins },
  { name: "系统配置", href: "/settings", icon: Settings },
]

export function AdminSidebar() {
  const pathname = usePathname()

  return (
    <aside className="fixed left-0 top-0 z-40 h-screen w-64 border-r border-border bg-sidebar">
      <div className="flex h-full flex-col">
        <div className="flex h-16 items-center gap-3 border-b border-sidebar-border px-6">
          <div className="flex h-9 w-9 items-center justify-center rounded-xl bg-primary">
            <Sparkles className="h-5 w-5 text-primary-foreground" />
          </div>
          <div>
            <h1 className="text-base font-semibold text-sidebar-foreground">AI 工具超市</h1>
            <p className="text-xs text-muted-foreground">管理后台</p>
          </div>
        </div>

        <nav className="flex-1 space-y-1 px-3 py-4">
          {navigation.map((item) => {
            const isActive = pathname === item.href
            return (
              <Link
                key={item.name}
                href={item.href}
                className={cn(
                  "flex items-center gap-3 rounded-lg px-3 py-2.5 text-sm font-medium transition-all duration-200",
                  isActive
                    ? "bg-sidebar-accent text-sidebar-foreground"
                    : "text-muted-foreground hover:bg-sidebar-accent/50 hover:text-sidebar-foreground",
                )}
              >
                <item.icon className="h-4.5 w-4.5" />
                {item.name}
              </Link>
            )
          })}
        </nav>

        <div className="border-t border-sidebar-border p-4">
          <div className="flex items-center gap-3">
            <div className="h-8 w-8 rounded-full bg-secondary" />
            <div className="flex-1">
              <p className="text-sm font-medium text-sidebar-foreground">管理员</p>
              <p className="text-xs text-muted-foreground">admin@ai.com</p>
            </div>
          </div>
        </div>
      </div>
    </aside>
  )
}
