"use client"

import { useState } from "react"
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
  DialogHeader,
  DialogTitle,
  DialogTrigger,
  DialogFooter,
} from "@/components/ui/dialog"
import { Label } from "@/components/ui/label"
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select"
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs"
import {
  Search,
  Plus,
  Minus,
  ArrowUpRight,
  ArrowDownLeft,
  Coins,
  TrendingUp,
  Users,
  Zap,
} from "lucide-react"
import { cn } from "@/lib/utils"

const creditRecords = [
  {
    id: "C001",
    user: "张三",
    type: "consume",
    amount: -5,
    balance: 495,
    reason: "AI 朋友圈文案生成器",
    time: "2024-05-09 14:32:28",
  },
  {
    id: "C002",
    user: "李四",
    type: "recharge",
    amount: 100,
    balance: 220,
    reason: "购买基础版套餐",
    time: "2024-05-09 14:30:00",
  },
  {
    id: "C003",
    user: "王五",
    type: "consume",
    amount: -10,
    balance: 90,
    reason: "AI 短视频脚本生成器",
    time: "2024-05-09 14:28:15",
  },
  {
    id: "C004",
    user: "赵六",
    type: "admin",
    amount: 50,
    balance: 2050,
    reason: "管理员手动充值",
    time: "2024-05-09 14:25:00",
  },
  {
    id: "C005",
    user: "孙七",
    type: "consume",
    amount: -15,
    balance: 265,
    reason: "AI 公众号长文生成器",
    time: "2024-05-09 14:16:45",
  },
  {
    id: "C006",
    user: "张三",
    type: "refund",
    amount: 10,
    balance: 500,
    reason: "任务失败退款",
    time: "2024-05-09 14:10:00",
  },
]

const userCredits = [
  { id: "U001", name: "张三", email: "zhangsan@example.com", credits: 500, plan: "专业版" },
  { id: "U002", name: "李四", email: "lisi@example.com", credits: 220, plan: "基础版" },
  { id: "U003", name: "王五", email: "wangwu@example.com", credits: 90, plan: "免费版" },
  { id: "U004", name: "赵六", email: "zhaoliu@example.com", credits: 2050, plan: "企业版" },
  { id: "U005", name: "孙七", email: "sunqi@example.com", credits: 265, plan: "专业版" },
]

const stats = [
  { label: "总算力发放", value: "1,285,000", icon: Coins, color: "text-primary" },
  { label: "总算力消耗", value: "986,420", icon: Zap, color: "text-accent" },
  { label: "剩余总算力", value: "298,580", icon: TrendingUp, color: "text-chart-5" },
  { label: "付费用户", value: "3,842", icon: Users, color: "text-chart-3" },
]

export default function CreditsPage() {
  const [searchQuery, setSearchQuery] = useState("")
  const [isAdjustDialogOpen, setIsAdjustDialogOpen] = useState(false)
  const [adjustType, setAdjustType] = useState<"add" | "subtract">("add")
  const [selectedUser, setSelectedUser] = useState("")

  const creditColumns = [
    { key: "id" as const, title: "流水 ID" },
    { key: "user" as const, title: "用户" },
    {
      key: "type" as const,
      title: "类型",
      render: (value: unknown) => {
        const typeMap = {
          consume: { label: "消耗", color: "bg-destructive/10 text-destructive" },
          recharge: { label: "充值", color: "bg-accent/10 text-accent" },
          admin: { label: "管理员", color: "bg-primary/10 text-primary" },
          refund: { label: "退款", color: "bg-chart-5/10 text-chart-5" },
        }
        const type = typeMap[value as keyof typeof typeMap]
        return (
          <Badge variant="secondary" className={cn("font-medium", type.color)}>
            {type.label}
          </Badge>
        )
      },
    },
    {
      key: "amount" as const,
      title: "变动",
      render: (value: unknown) => {
        const amount = value as number
        return (
          <span
            className={cn(
              "flex items-center gap-1 font-medium",
              amount > 0 ? "text-accent" : "text-destructive"
            )}
          >
            {amount > 0 ? (
              <ArrowUpRight className="h-4 w-4" />
            ) : (
              <ArrowDownLeft className="h-4 w-4" />
            )}
            {amount > 0 ? `+${amount}` : amount}
          </span>
        )
      },
    },
    {
      key: "balance" as const,
      title: "余额",
      render: (value: unknown) => <span>{value as number} 点</span>,
    },
    { key: "reason" as const, title: "原因" },
    { key: "time" as const, title: "时间" },
  ]

  const userColumns = [
    { key: "id" as const, title: "用户 ID" },
    {
      key: "name" as const,
      title: "用户",
      render: (_: unknown, item: (typeof userCredits)[0]) => (
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
    {
      key: "plan" as const,
      title: "套餐",
      render: (value: unknown) => (
        <Badge variant="secondary">{value as string}</Badge>
      ),
    },
    {
      key: "credits" as const,
      title: "算力余额",
      render: (value: unknown) => (
        <span className="font-semibold">{value as number} 点</span>
      ),
    },
    {
      key: "actions" as const,
      title: "操作",
      render: (_: unknown, item: (typeof userCredits)[0]) => (
        <div className="flex items-center gap-2">
          <Button
            variant="ghost"
            size="sm"
            className="gap-1 text-accent hover:text-accent"
            onClick={() => {
              setSelectedUser(item.id)
              setAdjustType("add")
              setIsAdjustDialogOpen(true)
            }}
          >
            <Plus className="h-4 w-4" />
            加算力
          </Button>
          <Button
            variant="ghost"
            size="sm"
            className="gap-1 text-destructive hover:text-destructive"
            onClick={() => {
              setSelectedUser(item.id)
              setAdjustType("subtract")
              setIsAdjustDialogOpen(true)
            }}
          >
            <Minus className="h-4 w-4" />
            扣算力
          </Button>
        </div>
      ),
    },
  ]

  return (
    <AdminLayout>
      <AdminHeader
        title="会员算力"
        description="管理用户算力余额和消耗记录"
      />

      <div className="p-6 space-y-6">
        {/* Stats */}
        <div className="grid gap-4 md:grid-cols-4">
          {stats.map((stat) => (
            <div
              key={stat.label}
              className="rounded-xl border border-border bg-card p-4"
            >
              <div className="flex items-center gap-3">
                <div className="rounded-lg bg-secondary p-2">
                  <stat.icon className={`h-5 w-5 ${stat.color}`} />
                </div>
                <div>
                  <p className="text-2xl font-semibold text-card-foreground">
                    {stat.value}
                  </p>
                  <p className="text-sm text-muted-foreground">{stat.label}</p>
                </div>
              </div>
            </div>
          ))}
        </div>

        {/* Tabs */}
        <Tabs defaultValue="records" className="space-y-4">
          <TabsList className="bg-secondary">
            <TabsTrigger value="records">算力流水</TabsTrigger>
            <TabsTrigger value="users">用户算力</TabsTrigger>
          </TabsList>

          <TabsContent value="records" className="space-y-4">
            <div className="flex items-center gap-4">
              <div className="relative flex-1 max-w-md">
                <Search className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
                <Input
                  placeholder="搜索用户或流水 ID..."
                  value={searchQuery}
                  onChange={(e) => setSearchQuery(e.target.value)}
                  className="pl-9 bg-secondary border-0"
                />
              </div>
            </div>
            <DataTable columns={creditColumns} data={creditRecords} />
          </TabsContent>

          <TabsContent value="users" className="space-y-4">
            <div className="flex items-center gap-4">
              <div className="relative flex-1 max-w-md">
                <Search className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
                <Input
                  placeholder="搜索用户..."
                  className="pl-9 bg-secondary border-0"
                />
              </div>
            </div>
            <DataTable columns={userColumns} data={userCredits} />
          </TabsContent>
        </Tabs>

        {/* Adjust Dialog */}
        <Dialog open={isAdjustDialogOpen} onOpenChange={setIsAdjustDialogOpen}>
          <DialogContent className="bg-card border-border">
            <DialogHeader>
              <DialogTitle>
                {adjustType === "add" ? "增加算力" : "扣除算力"}
              </DialogTitle>
              <DialogDescription>
                手动调整用户算力余额
              </DialogDescription>
            </DialogHeader>
            <div className="space-y-4 py-4">
              <div className="space-y-2">
                <Label>用户</Label>
                <Select value={selectedUser} onValueChange={setSelectedUser}>
                  <SelectTrigger className="bg-secondary border-0">
                    <SelectValue placeholder="选择用户" />
                  </SelectTrigger>
                  <SelectContent className="bg-card border-border">
                    {userCredits.map((user) => (
                      <SelectItem key={user.id} value={user.id}>
                        {user.name} ({user.email})
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>
              <div className="space-y-2">
                <Label>算力数量</Label>
                <Input
                  type="number"
                  placeholder="输入算力数量"
                  className="bg-secondary border-0"
                />
              </div>
              <div className="space-y-2">
                <Label>原因</Label>
                <Input
                  placeholder="输入调整原因"
                  className="bg-secondary border-0"
                />
              </div>
            </div>
            <DialogFooter>
              <Button
                variant="outline"
                onClick={() => setIsAdjustDialogOpen(false)}
              >
                取消
              </Button>
              <Button
                className={
                  adjustType === "add"
                    ? "bg-accent hover:bg-accent/90"
                    : "bg-destructive hover:bg-destructive/90"
                }
                onClick={() => setIsAdjustDialogOpen(false)}
              >
                确认{adjustType === "add" ? "增加" : "扣除"}
              </Button>
            </DialogFooter>
          </DialogContent>
        </Dialog>
      </div>
    </AdminLayout>
  )
}
