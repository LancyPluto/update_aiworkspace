"use client"

import { useEffect, useMemo, useState } from "react"
import { AdminLayout } from "@/components/admin/admin-layout"
import { AdminHeader } from "@/components/admin/header"
import { DataTable, StatusBadge } from "@/components/admin/data-table"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from "@/components/ui/dialog"
import { Search, Filter, Eye, Ban, Coins } from "lucide-react"
import { fetchAdminUsers, manualAddCredits } from "@/lib/api/users"
import { ApiError } from "@/lib/api/http"
import type { AdminMember } from "@/lib/api/types"

interface UserRow {
  id: string
  rawId: number
  name: string
  email: string
  phone: string
  plan: string
  credits: number
  tasks: number | string
  status: "active" | "inactive"
  statusLabel: string
  createdAt: string
}

function mapMember(m: AdminMember): UserRow {
  const isActive = (m.status || "ACTIVE").toUpperCase() === "ACTIVE"
  return {
    id: `U${String(m.id).padStart(3, "0")}`,
    rawId: m.id,
    name: m.nickname || m.username || `用户${m.id}`,
    email: m.username || "-",
    phone: "-",
    plan: m.userType === "ADMIN" ? "管理员" : "普通用户",
    credits: m.credits ?? 0,
    tasks: "-",
    status: isActive ? "active" : "inactive",
    statusLabel: isActive ? "正常" : "已禁用",
    createdAt: m.createdAt ? m.createdAt.replace("T", " ").slice(0, 19) : "-",
  }
}

export default function UsersPage() {
  const [searchQuery, setSearchQuery] = useState("")
  const [statusFilter, setStatusFilter] = useState("all")
  const [users, setUsers] = useState<UserRow[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [adjustingId, setAdjustingId] = useState<number | null>(null)

  const loadUsers = async () => {
    setLoading(true)
    setError(null)
    try {
      const resp = await fetchAdminUsers()
      setUsers(resp.list.map(mapMember))
    } catch (err) {
      const message = err instanceof ApiError ? err.message : "加载用户列表失败"
      setError(message)
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    loadUsers()
  }, [])

  const filteredUsers = useMemo(() => {
    return users.filter((user) => {
      const matchesSearch =
        user.name.includes(searchQuery) ||
        user.email.includes(searchQuery) ||
        user.phone.includes(searchQuery)
      const matchesStatus =
        statusFilter === "all" || user.status === statusFilter
      return matchesSearch && matchesStatus
    })
  }, [users, searchQuery, statusFilter])

  const handleAdjustCredits = async (item: UserRow) => {
    if (typeof window === "undefined") return
    const input = window.prompt(
      `为「${item.name}」(ID:${item.rawId}) 增加算力，当前余额：${item.credits}\n请输入要增加的算力数量：`,
      "100",
    )
    if (input == null) return
    const amount = Number(input.trim())
    if (!Number.isFinite(amount) || amount <= 0) {
      window.alert("请输入大于 0 的整数")
      return
    }
    const reason =
      window.prompt("请输入备注原因（将记录在算力流水中）", "运营手动加算力") ||
      "运营手动加算力"
    setAdjustingId(item.rawId)
    try {
      const result = await manualAddCredits(item.rawId, {
        amount: Math.floor(amount),
        reason,
      })
      window.alert(
        `已为 ${item.name} 增加 ${result.amount} 算力（${result.balanceBefore} → ${result.balanceAfter}）`,
      )
      await loadUsers()
    } catch (err) {
      const message = err instanceof ApiError ? err.message : "加算力失败"
      window.alert(message)
    } finally {
      setAdjustingId(null)
    }
  }

  const userColumns = [
    { key: "id" as const, title: "用户 ID" },
    {
      key: "name" as const,
      title: "用户",
      render: (_: unknown, item: UserRow) => (
        <div className="flex items-center gap-3">
          <div className="h-8 w-8 rounded-full bg-secondary flex items-center justify-center text-sm font-medium">
            {item.name[0]}
          </div>
          <div>
            <p className="font-medium">{item.name}</p>
            <p className="text-xs text-muted-foreground">{item.email}</p>
          </div>
        </div>
      ),
    },
    { key: "phone" as const, title: "手机号" },
    {
      key: "plan" as const,
      title: "套餐",
      render: (value: unknown) => (
        <span className="rounded-md bg-secondary px-2 py-1 text-xs font-medium">
          {value as string}
        </span>
      ),
    },
    {
      key: "credits" as const,
      title: "算力余额",
      render: (value: unknown) => <span>{value as number} 点</span>,
    },
    {
      key: "tasks" as const,
      title: "任务数",
    },
    {
      key: "status" as const,
      title: "状态",
      render: (_: unknown, item: UserRow) => (
        <StatusBadge status={item.status} label={item.statusLabel} />
      ),
    },
    { key: "createdAt" as const, title: "注册时间" },
    {
      key: "actions" as const,
      title: "操作",
      render: (_: unknown, item: UserRow) => (
        <div className="flex items-center gap-2">
          <Dialog>
            <DialogTrigger asChild>
              <Button variant="ghost" size="icon" className="h-8 w-8">
                <Eye className="h-4 w-4" />
              </Button>
            </DialogTrigger>
            <DialogContent className="bg-card border-border">
              <DialogHeader>
                <DialogTitle>用户详情</DialogTitle>
                <DialogDescription>查看用户 {item.name} 的详细信息</DialogDescription>
              </DialogHeader>
              <div className="space-y-4 py-4">
                <div className="grid grid-cols-2 gap-4">
                  <div>
                    <p className="text-sm text-muted-foreground">用户名</p>
                    <p className="font-medium">{item.name}</p>
                  </div>
                  <div>
                    <p className="text-sm text-muted-foreground">邮箱</p>
                    <p className="font-medium">{item.email}</p>
                  </div>
                  <div>
                    <p className="text-sm text-muted-foreground">手机号</p>
                    <p className="font-medium">{item.phone}</p>
                  </div>
                  <div>
                    <p className="text-sm text-muted-foreground">套餐</p>
                    <p className="font-medium">{item.plan}</p>
                  </div>
                  <div>
                    <p className="text-sm text-muted-foreground">算力余额</p>
                    <p className="font-medium">{item.credits} 点</p>
                  </div>
                  <div>
                    <p className="text-sm text-muted-foreground">任务数</p>
                    <p className="font-medium">{item.tasks}</p>
                  </div>
                </div>
              </div>
            </DialogContent>
          </Dialog>
          <Button
            variant="ghost"
            size="icon"
            className="h-8 w-8"
            disabled={adjustingId === item.rawId}
            onClick={() => handleAdjustCredits(item)}
          >
            <Coins className="h-4 w-4" />
          </Button>
          <Button
            variant="ghost"
            size="icon"
            className="h-8 w-8 text-destructive hover:text-destructive"
            disabled
            title="V1 暂不支持禁用用户"
          >
            <Ban className="h-4 w-4" />
          </Button>
        </div>
      ),
    },
  ]

  const description = error
    ? `加载失败：${error}`
    : loading
      ? "正在加载用户列表..."
      : "管理平台用户，查看用户信息和任务记录"

  return (
    <AdminLayout>
      <AdminHeader title="用户管理" description={description} />

      <div className="p-6 space-y-6">
        {/* Filters */}
        <div className="flex items-center gap-4">
          <div className="relative flex-1 max-w-md">
            <Search className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
            <Input
              placeholder="搜索用户名、邮箱或手机号..."
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              className="pl-9 bg-secondary border-0"
            />
          </div>
          <Select value={statusFilter} onValueChange={setStatusFilter}>
            <SelectTrigger className="w-40 bg-secondary border-0">
              <Filter className="mr-2 h-4 w-4" />
              <SelectValue placeholder="状态筛选" />
            </SelectTrigger>
            <SelectContent className="bg-card border-border">
              <SelectItem value="all">全部状态</SelectItem>
              <SelectItem value="active">正常</SelectItem>
              <SelectItem value="inactive">已禁用</SelectItem>
            </SelectContent>
          </Select>
        </div>

        {/* Users Table */}
        <DataTable columns={userColumns} data={filteredUsers} />
      </div>
    </AdminLayout>
  )
}
