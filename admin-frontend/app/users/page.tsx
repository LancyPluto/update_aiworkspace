"use client"

import { useEffect, useMemo, useState } from "react"
import { AdminLayout } from "@/components/admin/admin-layout"
import { AdminHeader } from "@/components/admin/header"
import { DataTable, StatusBadge } from "@/components/admin/data-table"
import { Button } from "@/components/ui/button"
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle, DialogTrigger } from "@/components/ui/dialog"
import { Input } from "@/components/ui/input"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { ApiError } from "@/lib/api/http"
import { fetchAdminUsers, manualAddCredits, memberAccountBalance, updateUserStatus } from "@/lib/api/users"
import type { AdminMember } from "@/lib/api/types"
import { Ban, Eye, Filter, Search, Undo2 } from "lucide-react"
import { CreditPowerIcon } from "@/components/admin/credit-power-icon"

interface UserRow {
  id: string
  rawId: number
  name: string
  account: string
  phone?: string | null
  email?: string | null
  userType: string
  credits: number
  status: "active" | "inactive"
  statusLabel: string
  createdAt: string
}

function mapUser(user: AdminMember): UserRow {
  const active = (user.status || "ACTIVE").toUpperCase() === "ACTIVE"
  return {
    id: `U${String(user.id).padStart(3, "0")}`,
    rawId: user.id,
    name: user.nickname || user.username || `用户 ${user.id}`,
    account: user.username,
    phone: user.phone,
    email: user.email,
    userType: user.userType === "ADMIN" ? "管理员" : "普通用户",
    credits: memberAccountBalance(user),
    status: active ? "active" : "inactive",
    statusLabel: active ? "正常" : "已禁用",
    createdAt: user.createdAt ? user.createdAt.replace("T", " ").slice(0, 19) : "-",
  }
}

export default function UsersPage() {
  const [users, setUsers] = useState<UserRow[]>([])
  const [searchQuery, setSearchQuery] = useState("")
  const [statusFilter, setStatusFilter] = useState("all")
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [actionId, setActionId] = useState<number | null>(null)

  async function loadUsers() {
    setLoading(true)
    setError(null)
    try {
      const response = await fetchAdminUsers()
      setUsers(response.list.map(mapUser))
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "加载用户列表失败")
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    loadUsers()
  }, [])

  const filtered = useMemo(() => {
    const keyword = searchQuery.trim().toLowerCase()
    return users.filter((user) => {
      const matchesKeyword = !keyword || [user.id, user.name, user.account, user.userType].some((value) => value.toLowerCase().includes(keyword))
      const matchesStatus = statusFilter === "all" || user.status === statusFilter
      return matchesKeyword && matchesStatus
    })
  }, [users, searchQuery, statusFilter])

  async function handleCredits(user: UserRow) {
    const rawAmount = window.prompt(`为 ${user.name} 增加算力，当前余额 ${user.credits}`, "100")
    if (rawAmount == null) return
    const amount = Number(rawAmount)
    if (!Number.isFinite(amount) || amount <= 0) {
      window.alert("请输入大于 0 的算力数量")
      return
    }
    const reason = window.prompt("请输入调整原因", "运营手动加算力") || "运营手动加算力"
    setActionId(user.rawId)
    try {
      await manualAddCredits(user.rawId, { amount: Math.floor(amount), reason })
      await loadUsers()
    } catch (err) {
      window.alert(err instanceof ApiError ? err.message : "调整算力失败")
    } finally {
      setActionId(null)
    }
  }

  async function handleStatus(user: UserRow) {
    const nextStatus = user.status === "active" ? "DISABLED" : "ACTIVE"
    const reason = nextStatus === "ACTIVE" ? "管理员恢复用户" : "管理员禁用用户"
    setActionId(user.rawId)
    try {
      await updateUserStatus(user.rawId, { status: nextStatus, reason })
      await loadUsers()
    } catch (err) {
      window.alert(err instanceof ApiError ? err.message : "更新用户状态失败")
    } finally {
      setActionId(null)
    }
  }

  const columns = [
    { key: "id" as const, title: "用户 ID" },
    {
      key: "name" as const,
      title: "用户",
      render: (_: unknown, item: UserRow) => (
        <div>
          <p className="font-medium">{item.name}</p>
          <p className="text-xs text-muted-foreground">{item.account}</p>
        </div>
      ),
    },
    { key: "userType" as const, title: "类型" },
    { key: "credits" as const, title: "算力余额" },
    {
      key: "status" as const,
      title: "状态",
      render: (_: unknown, item: UserRow) => <StatusBadge status={item.status} label={item.statusLabel} />,
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
            <DialogContent>
              <DialogHeader>
                <DialogTitle>用户详情</DialogTitle>
                <DialogDescription>来自后端用户和算力账户数据</DialogDescription>
              </DialogHeader>
              <div className="grid grid-cols-2 gap-4 text-sm">
                <div><p className="text-muted-foreground">账号</p><p className="font-medium">{item.account}</p></div>
                <div><p className="text-muted-foreground">类型</p><p className="font-medium">{item.userType}</p></div>
                <div><p className="text-muted-foreground">算力余额</p><p className="font-medium">{item.credits}</p></div>
                <div><p className="text-muted-foreground">状态</p><p className="font-medium">{item.statusLabel}</p></div>
                {item.phone ? (
                  <div><p className="text-muted-foreground">手机</p><p className="font-medium">{item.phone}</p></div>
                ) : null}
                {item.email ? (
                  <div><p className="text-muted-foreground">邮箱</p><p className="font-medium">{item.email}</p></div>
                ) : null}
              </div>
            </DialogContent>
          </Dialog>
          <Button variant="ghost" size="icon" className="h-8 w-8" disabled={actionId === item.rawId} onClick={() => handleCredits(item)}>
            <CreditPowerIcon className="h-4 w-4" />
          </Button>
          <Button variant="ghost" size="icon" className="h-8 w-8" disabled={actionId === item.rawId} onClick={() => handleStatus(item)}>
            {item.status === "active" ? <Ban className="h-4 w-4" /> : <Undo2 className="h-4 w-4" />}
          </Button>
        </div>
      ),
    },
  ]

  const description = error
    ? `联调异常：${error}`
    : loading
      ? "正在从后端加载用户列表"
      : "用户列表、算力调整和启停状态已连接数据库"

  return (
    <AdminLayout>
      <AdminHeader title="用户管理" description={description} />
      <div className="p-6 space-y-6">
        <div className="flex items-center gap-4">
          <div className="relative max-w-md flex-1">
            <Search className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
            <Input value={searchQuery} onChange={(event) => setSearchQuery(event.target.value)} placeholder="搜索用户、账号或类型" className="pl-9" />
          </div>
          <Select value={statusFilter} onValueChange={setStatusFilter}>
            <SelectTrigger className="w-40">
              <Filter className="mr-2 h-4 w-4" />
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="all">全部状态</SelectItem>
              <SelectItem value="active">正常</SelectItem>
              <SelectItem value="inactive">已禁用</SelectItem>
            </SelectContent>
          </Select>
        </div>
        <DataTable columns={columns} data={filtered} />
      </div>
    </AdminLayout>
  )
}
