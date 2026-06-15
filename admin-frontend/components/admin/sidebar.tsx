"use client"

import Link from "next/link"
import { usePathname } from "next/navigation"
import { useEffect, useState } from "react"
import {
  ChevronDown,
  Coins,
  FileText,
  Images,
  LayoutDashboard,
  ListTodo,
  ReceiptText,
  Settings,
  SlidersHorizontal,
  Users,
  Wrench,
  type LucideIcon,
} from "lucide-react"
import {
  Collapsible,
  CollapsibleContent,
  CollapsibleTrigger,
} from "@/components/ui/collapsible"
import { cn } from "@/lib/utils"

type NavLink = {
  type: "link"
  name: string
  href: string
  icon: LucideIcon
}

type NavGroup = {
  type: "group"
  id: string
  name: string
  icon: LucideIcon
  children: { name: string; href: string }[]
}

const navigation: (NavLink | NavGroup)[] = [
  { type: "link", name: "数据概览", href: "/", icon: LayoutDashboard },
  { type: "link", name: "用户管理", href: "/users", icon: Users },
  {
    type: "group",
    id: "ai-tools",
    name: "AI 工具管理",
    icon: Wrench,
    children: [
      { name: "工具配置", href: "/tools" },
      { name: "工作流", href: "/task-tools" },
    ],
  },
  { type: "link", name: "Prompt 管理", href: "/prompts", icon: FileText },
  { type: "link", name: "任务管理", href: "/tasks", icon: ListTodo },
  { type: "link", name: "社区作品", href: "/community-posts", icon: Images },
  { type: "link", name: "计费日志", href: "/billing", icon: ReceiptText },
  { type: "link", name: "定价配置", href: "/pricing", icon: SlidersHorizontal },
  { type: "link", name: "会员算力", href: "/credits", icon: Coins },
  { type: "link", name: "系统配置", href: "/settings", icon: Settings },
]

const EXPANDED_GROUPS_KEY = "admin_nav_expanded_groups"

function isPathActive(pathname: string, href: string) {
  if (href === "/") return pathname === "/"
  return pathname === href || pathname.startsWith(`${href}/`)
}

function isGroupActive(pathname: string, group: NavGroup) {
  return group.children.some((child) => isPathActive(pathname, child.href))
}

function NavGroupItem({ group, pathname }: { group: NavGroup; pathname: string }) {
  const groupActive = isGroupActive(pathname, group)
  const [open, setOpen] = useState(groupActive)

  useEffect(() => {
    if (groupActive) setOpen(true)
  }, [groupActive])

  useEffect(() => {
    try {
      const saved = localStorage.getItem(EXPANDED_GROUPS_KEY)
      const parsed = saved ? (JSON.parse(saved) as string[]) : []
      if (Array.isArray(parsed)) {
        setOpen(parsed.includes(group.id) || groupActive)
      }
    } catch {
      // Ignore invalid local storage.
    }
  }, [group.id, groupActive])

  function handleOpenChange(nextOpen: boolean) {
    setOpen(nextOpen)
    try {
      const saved = localStorage.getItem(EXPANDED_GROUPS_KEY)
      const parsed = saved ? (JSON.parse(saved) as string[]) : []
      const current = Array.isArray(parsed) ? parsed : []
      const next = nextOpen
        ? [...new Set([...current, group.id])]
        : current.filter((id) => id !== group.id)
      localStorage.setItem(EXPANDED_GROUPS_KEY, JSON.stringify(next))
    } catch {
      // Ignore storage errors.
    }
  }

  return (
    <Collapsible open={open} onOpenChange={handleOpenChange}>
      <CollapsibleTrigger
        className={cn(
          "flex w-full items-center gap-3 rounded-lg px-3 py-2.5 text-sm font-medium transition-all duration-200",
          groupActive
            ? "bg-sidebar-accent text-sidebar-foreground"
            : "text-muted-foreground hover:bg-sidebar-accent/50 hover:text-sidebar-foreground",
        )}
      >
        <group.icon className="h-4.5 w-4.5 shrink-0" />
        <span className="flex-1 text-left">{group.name}</span>
        <ChevronDown
          className={cn(
            "h-4 w-4 shrink-0 text-muted-foreground transition-transform duration-200",
            open && "rotate-180",
          )}
        />
      </CollapsibleTrigger>

      <CollapsibleContent className="data-[state=closed]:animate-accordion-up data-[state=open]:animate-accordion-down overflow-hidden">
        <ul className="mt-1 ml-5 space-y-0.5 border-l border-sidebar-border/70 pl-3">
          {group.children.map((child) => {
            const childActive = isPathActive(pathname, child.href)
            return (
              <li key={child.href}>
                <Link
                  href={child.href}
                  className={cn(
                    "block rounded-lg py-2 pl-3 pr-2 text-[13px] font-medium transition-all duration-200",
                    childActive
                      ? "bg-sidebar-accent text-sidebar-foreground"
                      : "text-muted-foreground hover:bg-sidebar-accent/50 hover:text-sidebar-foreground",
                  )}
                >
                  {child.name}
                </Link>
              </li>
            )
          })}
        </ul>
      </CollapsibleContent>
    </Collapsible>
  )
}

export function AdminSidebar() {
  const pathname = usePathname()
  const basePath = (process.env.NEXT_PUBLIC_ADMIN_BASE_PATH || "").replace(/\/$/, "")

  return (
    <aside className="fixed left-0 top-0 z-40 h-screen w-64 border-r border-border bg-sidebar">
      <div className="flex h-full flex-col">
        <div className="flex h-16 items-center gap-3 border-b border-sidebar-border px-6">
          <img src={`${basePath}/logo.svg`} alt="科创点AI" className="h-9 w-9 rounded-xl object-contain" />
          <div>
            <h1 className="text-base font-semibold text-sidebar-foreground">科创点AI</h1>
            <p className="text-xs text-muted-foreground">管理后台</p>
          </div>
        </div>

        <nav className="flex-1 space-y-1 overflow-y-auto px-3 py-4">
          {navigation.map((item) => {
            if (item.type === "group") {
              return <NavGroupItem key={item.id} group={item} pathname={pathname} />
            }

            const isActive = isPathActive(pathname, item.href)
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
            <div className="flex h-8 w-8 items-center justify-center rounded-full bg-secondary text-xs font-semibold">
              N
            </div>
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
