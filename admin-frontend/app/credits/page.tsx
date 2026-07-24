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
import { ArrowDownLeft, ArrowUpRight, Minus, Plus, Search, TrendingUp, Users } from "lucide-react"
import { toast } from "sonner"
import { CreditPowerIcon } from "@/components/admin/credit-power-icon"
import { cn } from "@/lib/utils"
import {
  fetchAdminUsers,
  fetchUserCreditLogs,
  manualAddCredits,
  manualDeductCredits,
  memberAccountBalance,
} from "@/lib/api/users"
import { ApiError } from "@/lib/api/http"
import { compareCreditLogsByCreatedAtDesc } from "@/lib/credit-log-sort"
import { adminUserDisplayName } from "@/lib/user-display-name"
import type { AdminMember, CreditLogItem } from "@/lib/api/types"

const CREDIT_RECORD_PAGE_SIZE = 20

interface CreditUserRow {
  id: string
  rawId: number
  name: string
  phone: string
  credits: number
  userType: string
}

interface CreditRecordRow {
  id: string
  logId: number
  createdAt: string | null
  user: string
  userPhone: string
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
    name: adminUserDisplayName(user),
    phone: user.phone || "",
    credits: memberAccountBalance(user),
    userType: user.userType,
  }
}

function formatTime(value?: string | null) {
  if (!value) return "-"
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return date.toLocaleString("zh-CN", {
    timeZone: "Asia/Shanghai",
    hour12: false,
  })
}

function signedCreditAmount(log: CreditLogItem) {
  const rawAmount = Number(log.amount || 0)
  const rawFrozenAmount = Number(log.frozenAmount || 0)
  const displayAmount = rawAmount !== 0 ? Math.abs(rawAmount) : Math.abs(rawFrozenAmount)
  if (displayAmount === 0) return 0
  if (["DEDUCT", "FREEZE", "MANUAL_DEDUCT"].includes(log.logType)) return -displayAmount
  return displayAmount
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
  const [giftCardOperationId, setGiftCardOperationId] = useState("")
  const [submitting, setSubmitting] = useState(false)
  const [recordPage, setRecordPage] = useState(1)

  const loadCredits = async () => {
    setLoading(true)
    setError(null)
    try {
      const userResp = await fetchAdminUsers({ pageNo: 1, pageSize: 200 })
      const mappedUsers = userResp.list.map(mapUser)
      setUsers(mappedUsers)

      const logGroups = await Promise.all(
        mappedUsers.map(async (user) => {
          try {
            const logResp = await fetchUserCreditLogs(user.rawId)
            return logResp.list.map((log: CreditLogItem): CreditRecordRow => ({
              id: `C${log.id}`,
              logId: log.id,
              createdAt: log.createdAt ?? null,
              user: user.name,
              userPhone: user.phone,
              type: log.logType,
              amount: signedCreditAmount(log),
              balance: log.balanceAfter,
              reason: log.reason || "-",
              time: formatTime(log.createdAt),
            }))
          } catch {
            return []
          }
        }),
      )
      setRecords(
        logGroups
          .flat()
          .sort((a, b) =>
            compareCreditLogsByCreatedAtDesc(
              { createdAt: a.createdAt, id: a.logId },
              { createdAt: b.createdAt, id: b.logId },
            ),
          ),
      )
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
      [record.id, record.user, record.userPhone, record.type, record.reason].some((value) =>
        value.toLowerCase().includes(searchQuery.toLowerCase()),
      ),
    )
  }, [records, searchQuery])

  useEffect(() => {
    setRecordPage(1)
  }, [searchQuery])

  const recordTotalPages = Math.max(1, Math.ceil(filteredRecords.length / CREDIT_RECORD_PAGE_SIZE))
  const pagedRecords = filteredRecords.slice(
    (recordPage - 1) * CREDIT_RECORD_PAGE_SIZE,
    recordPage * CREDIT_RECORD_PAGE_SIZE,
  )

  const filteredUsers = useMemo(() => {
    return users.filter((user) =>
      [user.id, user.name, user.phone, user.userType].some((value) =>
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
    { label: "用户当前总余额", value: totalGranted.toLocaleString(), icon: CreditPowerIcon, color: "text-primary" },
    { label: "已记录消耗", value: totalConsumed.toLocaleString(), icon: CreditPowerIcon, color: "text-accent" },
    { label: "流水记录数", value: records.length.toLocaleString(), icon: TrendingUp, color: "text-chart-5" },
    { label: "有余额用户", value: paidUsers.toLocaleString(), icon: Users, color: "text-chart-3" },
  ]

  const openAdjustDialog = (user: CreditUserRow, type: "add" | "subtract") => {
    setSelectedUser(String(user.rawId))
    setAdjustType(type)
    setAmount("100")
    setReason(type === "add" ? "运营发放礼品卡" : "运营手动扣算力")
    setGiftCardOperationId(type === "add" ? crypto.randomUUID() : "")
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
      const selected = users.find((user) => user.rawId === userId)
      if (adjustType === "add") {
        const operationId = giftCardOperationId || crypto.randomUUID()
        setGiftCardOperationId(operationId)
        const issued = await manualAddCredits(userId, { ...payload, operationId })
        toast.success(`已发送 ${issued.giftCard.credits} 算力礼品卡`, {
          description: `${selected?.name || `用户 #${userId}`} · 卡号 ${issued.giftCard.cardCode} · 等待用户兑换`,
        })
      } else {
        const updated = await manualDeductCredits(userId, payload)
        toast.success(`已扣除 ${payload.amount} 算力`, {
          description: `${selected?.name || `用户 #${userId}`} 当前余额 ${updated.balance}`,
        })
      }
      setIsAdjustDialogOpen(false)
      await loadCredits()
    } catch (err) {
      const message = err instanceof ApiError ? err.message : "调整算力失败"
      setError(message)
      toast.error("调整算力失败", { description: message })
    } finally {
      setSubmitting(false)
    }
  }

  const creditColumns = [
    { key: "id" as const, title: "流水 ID" },
    {
      key: "user" as const,
      title: "用户",
      render: (_: unknown, item: CreditRecordRow) => (
        <div>
          <p className="font-medium">{item.user}</p>
          <p className="text-xs text-muted-foreground">{item.userPhone || "--"}</p>
        </div>
      ),
    },
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
          <p className="text-xs text-muted-foreground">{item.phone || "--"}</p>
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
            <Plus className="h-4 w-4" /> 发礼品卡
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
            <div className="space-y-3">
              <DataTable columns={creditColumns} data={pagedRecords} />
              <div className="flex items-center justify-between rounded-xl border border-border bg-card px-4 py-3 text-sm text-muted-foreground">
                <span>共 {filteredRecords.length.toLocaleString()} 条，每页 {CREDIT_RECORD_PAGE_SIZE} 条</span>
                <div className="flex items-center gap-2">
                  <Button
                    variant="outline"
                    size="sm"
                    disabled={recordPage <= 1}
                    onClick={() => setRecordPage((page) => Math.max(1, page - 1))}
                  >
                    上一页
                  </Button>
                  <span>{recordPage} / {recordTotalPages}</span>
                  <Button
                    variant="outline"
                    size="sm"
                    disabled={recordPage >= recordTotalPages}
                    onClick={() => setRecordPage((page) => Math.min(recordTotalPages, page + 1))}
                  >
                    下一页
                  </Button>
                </div>
              </div>
            </div>
          </TabsContent>

          <TabsContent value="users">
            <DataTable columns={userColumns} data={filteredUsers} />
          </TabsContent>
        </Tabs>

        <Dialog open={isAdjustDialogOpen} onOpenChange={setIsAdjustDialogOpen}>
          <DialogContent className="bg-card border-border">
            <DialogHeader>
              <DialogTitle>{adjustType === "add" ? "发送礼品卡" : "扣除算力"}</DialogTitle>
              <DialogDescription>
                {adjustType === "add"
                  ? "礼品卡发送后由用户手动兑换，兑换前不改变算力余额。"
                  : "扣除会写入后端算力账户，并生成流水记录。"}
              </DialogDescription>
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
                      {user.name} ({user.phone || "--"})
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
