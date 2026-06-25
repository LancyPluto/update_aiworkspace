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
        <div className="relative isolate flex h-[92px] items-center overflow-hidden border-b border-sidebar-border px-6 pb-4 pt-6">
          <div
            aria-hidden="true"
            className="pointer-events-none absolute inset-x-0 bottom-[-34px] top-0 z-0 opacity-95 blur-[18px]"
            style={{
              background:
                "radial-gradient(ellipse 178px 112px at 42px 34px, rgb(34 211 238 / 0.46) 0%, rgb(52 211 153 / 0.28) 38%, transparent 74%), radial-gradient(ellipse 164px 108px at 134px 52px, rgb(59 130 246 / 0.24) 0%, rgb(99 102 241 / 0.12) 44%, transparent 78%)",
            }}
          />
          <Link href="/" className="relative z-10 flex min-w-0 flex-col items-start">
            <img
              src={`${basePath}/logo.png`}
              alt="科创点AI"
              className="h-10 w-auto max-w-[156px] object-contain [filter:drop-shadow(0_0_8px_rgb(34_211_238_/_0.45))_drop-shadow(0_0_16px_rgb(52_211_153_/_0.24))]"
            />
            <span className="mt-0.5 text-xs font-medium text-muted-foreground">管理后台</span>
          </Link>
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
