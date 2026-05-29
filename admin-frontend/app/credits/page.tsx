"use client"

import { useEffect, useMemo, useState } from "react"
import { AdminLayout } from "@/components/admin/admin-layout"
import { AdminHeader } from "@/components/admin/header"
import { DataTable } from "@/components/admin/data-table"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Badge } from "@/components/ui/badge"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import { Label } from "@/components/ui/label"
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs"
import { ArrowDownLeft, ArrowUpRight, Coins, Minus, Plus, Search, TrendingUp, Users, Zap } from "lucide-react"
import { cn } from "@/lib/utils"
import {
  fetchAdminUsers,
  fetchUserCreditLogs,
  manualAddCredits,
  manualDeductCredits,
  memberAccountBalance,
} from "@/lib/api/users"
import { ApiError } from "@/lib/api/http"
import type { AdminMember, CreditLogItem } from "@/lib/api/types"

interface CreditUserRow {
  id: string
  rawId: number
  name: string
  account: string
  credits: number
  userType: string
}

interface CreditRecordRow {
  id: string
  user: string
  type: string
  amount: number
  balance: number
  reason: string
  time: string
}

function mapUser(user: AdminMember): CreditUserRow {
  return {
    id: `U${String(user.id).padStart(3, "0")}`,
    rawId: user.id,
    name: user.nickname || user.username,
    account: user.username,
    credits: memberAccountBalance(user),
    userType: user.userType,
  }
}

function formatTime(value?: string | null) {
  if (!value) return "-"
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return date.toLocaleString()
}

export default function CreditsPage() {
  const [searchQuery, setSearchQuery] = useState("")
  const [users, setUsers] = useState<CreditUserRow[]>([])
  const [records, setRecords] = useState<CreditRecordRow[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [isAdjustDialogOpen, setIsAdjustDialogOpen] = useState(false)
  const [adjustType, setAdjustType] = useState<"add" | "subtract">("add")
  const [selectedUser, setSelectedUser] = useState("")
  const [amount, setAmount] = useState("100")
  const [reason, setReason] = useState("运营手动调整")
  const [submitting, setSubmitting] = useState(false)

  const loadCredits = async () => {
    setLoading(true)
    setError(null)
    try {
      const userResp = await fetchAdminUsers()
      const mappedUsers = userResp.list.map(mapUser)
      setUsers(mappedUsers)

      const logGroups = await Promise.all(
        mappedUsers.slice(0, 10).map(async (user) => {
          try {
            const logResp = await fetchUserCreditLogs(user.rawId)
            return logResp.list.map((log: CreditLogItem): CreditRecordRow => ({
              id: `C${log.id}`,
              user: user.name,
              type: log.logType,
              amount: log.amount,
              balance: log.balanceAfter,
              reason: log.reason || "-",
              time: formatTime(log.createdAt),
            }))
          } catch {
            return []
          }
        }),
      )
      setRecords(logGroups.flat().sort((a, b) => b.id.localeCompare(a.id)))
    } catch (err) {
      const message = err instanceof ApiError ? err.message : "加载算力数据失败"
      setError(message)
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    loadCredits()
  }, [])

  const filteredRecords = useMemo(() => {
    return records.filter((record) =>
      [record.id, record.user, record.type, record.reason].some((value) =>
        value.toLowerCase().includes(searchQuery.toLowerCase()),
      ),
    )
  }, [records, searchQuery])

  const filteredUsers = useMemo(() => {
    return users.filter((user) =>
      [user.id, user.name, user.account, user.userType].some((value) =>
        value.toLowerCase().includes(searchQuery.toLowerCase()),
      ),
    )
  }, [users, searchQuery])

  const totalGranted = users.reduce((sum, user) => sum + user.credits, 0)
  const totalConsumed = records
    .filter((record) => record.amount < 0)
    .reduce((sum, record) => sum + Math.abs(record.amount), 0)
  const paidUsers = users.filter((user) => user.credits > 0).length

  const stats = [
    { label: "用户当前总余额", value: totalGranted.toLocaleString(), icon: Coins, color: "text-primary" },
    { label: "已记录消耗", value: totalConsumed.toLocaleString(), icon: Zap, color: "text-accent" },
    { label: "流水记录数", value: records.length.toLocaleString(), icon: TrendingUp, color: "text-chart-5" },
    { label: "有余额用户", value: paidUsers.toLocaleString(), icon: Users, color: "text-chart-3" },
  ]

  const openAdjustDialog = (user: CreditUserRow, type: "add" | "subtract") => {
    setSelectedUser(String(user.rawId))
    setAdjustType(type)
    setAmount("100")
    setReason(type === "add" ? "运营手动加算力" : "运营手动扣算力")
    setIsAdjustDialogOpen(true)
  }

  const submitAdjust = async () => {
    const userId = Number(selectedUser)
    const parsedAmount = Number(amount)
    if (!userId || !Number.isFinite(parsedAmount) || parsedAmount <= 0) {
      setError("请选择用户并输入大于 0 的算力数量")
      return
    }
    setSubmitting(true)
    setError(null)
    try {
      const payload = { amount: Math.floor(parsedAmount), reason }
      if (adjustType === "add") {
        await manualAddCredits(userId, payload)
      } else {
        await manualDeductCredits(userId, payload)
      }
      setIsAdjustDialogOpen(false)
      await loadCredits()
    } catch (err) {
      const message = err instanceof ApiError ? err.message : "调整算力失败"
      setError(message)
    } finally {
      setSubmitting(false)
    }
  }

  const creditColumns = [
    { key: "id" as const, title: "流水 ID" },
    { key: "user" as const, title: "用户" },
    {
      key: "type" as const,
      title: "类型",
      render: (value: unknown) => <Badge variant="secondary">{value as string}</Badge>,
    },
    {
      key: "amount" as const,
      title: "变动",
      render: (value: unknown) => {
        const valueNumber = value as number
        return (
          <span className={cn("flex items-center gap-1 font-medium", valueNumber >= 0 ? "text-accent" : "text-destructive")}>
            {valueNumber >= 0 ? <ArrowUpRight className="h-4 w-4" /> : <ArrowDownLeft className="h-4 w-4" />}
            {valueNumber > 0 ? `+${valueNumber}` : valueNumber}
          </span>
        )
      },
    },
    { key: "balance" as const, title: "余额" },
    { key: "reason" as const, title: "原因" },
    { key: "time" as const, title: "时间" },
  ]

  const userColumns = [
    { key: "id" as const, title: "用户 ID" },
    {
      key: "name" as const,
      title: "用户",
      render: (_: unknown, item: CreditUserRow) => (
        <div>
          <p className="font-medium">{item.name}</p>
          <p className="text-xs text-muted-foreground">{item.account}</p>
        </div>
      ),
    },
    { key: "userType" as const, title: "类型" },
    { key: "credits" as const, title: "算力余额" },
    {
      key: "actions" as const,
      title: "操作",
      render: (_: unknown, item: CreditUserRow) => (
        <div className="flex items-center gap-2">
          <Button variant="ghost" size="sm" className="gap-1 text-accent" onClick={() => openAdjustDialog(item, "add")}>
            <Plus className="h-4 w-4" /> 加算力
          </Button>
          <Button variant="ghost" size="sm" className="gap-1 text-destructive" onClick={() => openAdjustDialog(item, "subtract")}>
            <Minus className="h-4 w-4" /> 扣算力
          </Button>
        </div>
      ),
    },
  ]

  return (
    <AdminLayout>
      <AdminHeader title="会员算力" description={error ? `加载失败：${error}` : "连接后端算力账户与流水数据"} />

      <div className="p-6 space-y-6">
        <div className="grid gap-4 md:grid-cols-4">
          {stats.map((stat) => (
            <div key={stat.label} className="rounded-xl border border-border bg-card p-4">
              <div className="flex items-center gap-3">
                <div className="rounded-lg bg-secondary p-2">
                  <stat.icon className={`h-5 w-5 ${stat.color}`} />
                </div>
                <div>
                  <p className="text-2xl font-semibold text-card-foreground">
                    {loading ? "--" : stat.value}
                  </p>
                  <p className="text-sm text-muted-foreground">{stat.label}</p>
                </div>
              </div>
            </div>
          ))}
        </div>

        <Tabs defaultValue="records" className="space-y-4">
          <TabsList className="bg-secondary">
            <TabsTrigger value="records">算力流水</TabsTrigger>
            <TabsTrigger value="users">用户算力</TabsTrigger>
          </TabsList>

          <div className="relative max-w-md">
            <Search className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
            <Input
              placeholder="搜索用户、流水、类型或原因..."
              value={searchQuery}
              onChange={(event) => setSearchQuery(event.target.value)}
              className="pl-9 bg-secondary border-0"
            />
          </div>

          <TabsContent value="records">
            <DataTable columns={creditColumns} data={filteredRecords} />
          </TabsContent>

          <TabsContent value="users">
            <DataTable columns={userColumns} data={filteredUsers} />
          </TabsContent>
        </Tabs>

        <Dialog open={isAdjustDialogOpen} onOpenChange={setIsAdjustDialogOpen}>
          <DialogContent className="bg-card border-border">
            <DialogHeader>
              <DialogTitle>{adjustType === "add" ? "增加算力" : "扣除算力"}</DialogTitle>
              <DialogDescription>调整会写入后端算力账户，并生成流水记录。</DialogDescription>
            </DialogHeader>
            <div className="space-y-4 py-4">
              <div className="space-y-2">
                <Label>用户</Label>
                <select
                  value={selectedUser}
                  onChange={(event) => setSelectedUser(event.target.value)}
                  className="h-10 w-full rounded-md bg-secondary px-3 text-sm"
                >
                  <option value="">请选择用户</option>
                  {users.map((user) => (
                    <option key={user.rawId} value={user.rawId}>
                      {user.name} ({user.account})
                    </option>
                  ))}
                </select>
              </div>
              <div className="space-y-2">
                <Label>算力数量</Label>
                <Input value={amount} onChange={(event) => setAmount(event.target.value)} type="number" min="1" className="bg-secondary border-0" />
              </div>
              <div className="space-y-2">
                <Label>原因</Label>
                <Input value={reason} onChange={(event) => setReason(event.target.value)} className="bg-secondary border-0" />
              </div>
            </div>
            <DialogFooter>
              <Button variant="outline" onClick={() => setIsAdjustDialogOpen(false)}>取消</Button>
              <Button disabled={submitting} onClick={submitAdjust}>
                {submitting ? "提交中..." : "确认"}
              </Button>
            </DialogFooter>
          </DialogContent>
        </Dialog>
      </div>
    </AdminLayout>
  )
}
