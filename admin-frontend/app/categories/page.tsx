"use client"

import { useEffect, useState } from "react"
import { AdminLayout } from "@/components/admin/admin-layout"
import { AdminHeader } from "@/components/admin/header"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Switch } from "@/components/ui/switch"
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
  Plus,
  GripVertical,
  Pencil,
  Trash2,
  FileText,
  Video,
  ShoppingBag,
  MessageSquare,
  Store,
  type LucideIcon,
} from "lucide-react"
import { cn } from "@/lib/utils"
import { fetchAdminTools, fetchToolCategories } from "@/lib/api/tools"
import { ApiError } from "@/lib/api/http"
import type { ToolCategory, ToolSummary } from "@/lib/api/types"

interface Category {
  id: string
  rawId: number
  name: string
  description: string
  icon: LucideIcon
  toolCount: number
  sort: number
  status: boolean
}

const ICON_MAP: Record<string, LucideIcon> = {
  内容创作: FileText,
  短视频运营: Video,
  电商运营: ShoppingBag,
  私域销售: MessageSquare,
  门店获客: Store,
}

function pickIcon(name: string): LucideIcon {
  return ICON_MAP[name] || FileText
}

function buildDescription(name: string): string {
  const map: Record<string, string> = {
    内容创作: "文案、文章、社交媒体内容生成",
    短视频运营: "脚本、选题、直播话术等",
    电商运营: "标题优化、详情页文案、活动方案",
    私域销售: "跟进话术、异议处理、成交技巧",
    门店获客: "活动策划、引流方案、会员营销",
  }
  return map[name] || `${name} 相关 AI 工具`
}

export default function CategoriesPage() {
  const [categories, setCategories] = useState<Category[]>([])
  const [isAddDialogOpen, setIsAddDialogOpen] = useState(false)
  const [editingCategory, setEditingCategory] = useState<Category | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    async function load() {
      setLoading(true)
      setError(null)
      try {
        const [cats, toolsResp] = await Promise.all([
          fetchToolCategories(),
          fetchAdminTools().catch(() => ({ list: [], total: 0 })),
        ])
        if (cancelled) return
        const counts = new Map<number, number>()
        toolsResp.list.forEach((t: ToolSummary) => {
          counts.set(t.categoryId, (counts.get(t.categoryId) || 0) + 1)
        })
        const mapped: Category[] = cats.map((c: ToolCategory, idx) => ({
          id: String(c.id),
          rawId: c.id,
          name: c.categoryName,
          description: buildDescription(c.categoryName),
          icon: pickIcon(c.categoryName),
          toolCount: counts.get(c.id) || 0,
          sort: c.sortOrder ?? idx + 1,
          status: true,
        }))
        setCategories(mapped)
      } catch (err) {
        if (cancelled) return
        const message = err instanceof ApiError ? err.message : "加载分类失败"
        setError(message)
      } finally {
        if (!cancelled) setLoading(false)
      }
    }
    load()
    return () => {
      cancelled = true
    }
  }, [])

  const toggleCategoryStatus = (id: string) => {
    setCategories((prev) =>
      prev.map((cat) => (cat.id === id ? { ...cat, status: !cat.status } : cat)),
    )
  }

  const headerDescription = error
    ? `加载失败：${error}`
    : loading
      ? "正在加载分类..."
      : "管理 AI 工具分类，调整分类排序和状态"

  return (
    <AdminLayout>
      <AdminHeader title="分类管理" description={headerDescription} />

      <div className="p-6 space-y-6">
        {/* Actions */}
        <div className="flex items-center justify-end">
          <Dialog open={isAddDialogOpen} onOpenChange={setIsAddDialogOpen}>
            <DialogTrigger asChild>
              <Button className="gap-2" disabled title="V1 后台暂不支持新增分类，请通过 SQL 维护">
                <Plus className="h-4 w-4" />
                新增分类
              </Button>
            </DialogTrigger>
            <DialogContent className="bg-card border-border">
              <DialogHeader>
                <DialogTitle>新增分类</DialogTitle>
                <DialogDescription>创建新的工具分类</DialogDescription>
              </DialogHeader>
              <div className="space-y-4 py-4">
                <div className="space-y-2">
                  <Label>分类名称</Label>
                  <Input
                    placeholder="输入分类名称"
                    className="bg-secondary border-0"
                  />
                </div>
                <div className="space-y-2">
                  <Label>分类描述</Label>
                  <Input
                    placeholder="输入分类描述"
                    className="bg-secondary border-0"
                  />
                </div>
              </div>
              <DialogFooter>
                <Button
                  variant="outline"
                  onClick={() => setIsAddDialogOpen(false)}
                >
                  取消
                </Button>
                <Button onClick={() => setIsAddDialogOpen(false)}>
                  创建分类
                </Button>
              </DialogFooter>
            </DialogContent>
          </Dialog>
        </div>

        {/* Categories List */}
        <div className="space-y-3">
          {categories.map((category) => (
            <div
              key={category.id}
              className={cn(
                "group flex items-center gap-4 rounded-xl border border-border bg-card p-4 transition-all duration-200 hover:border-primary/30",
                !category.status && "opacity-60",
              )}
            >
              <button className="cursor-grab text-muted-foreground hover:text-foreground active:cursor-grabbing">
                <GripVertical className="h-5 w-5" />
              </button>

              <div className="flex h-12 w-12 items-center justify-center rounded-xl bg-primary/10">
                <category.icon className="h-6 w-6 text-primary" />
              </div>

              <div className="flex-1">
                <div className="flex items-center gap-2">
                  <h3 className="font-semibold text-card-foreground">
                    {category.name}
                  </h3>
                  <span className="rounded-md bg-secondary px-2 py-0.5 text-xs text-muted-foreground">
                    {category.toolCount} 个工具
                  </span>
                </div>
                <p className="mt-0.5 text-sm text-muted-foreground">
                  {category.description}
                </p>
              </div>

              <div className="flex items-center gap-3">
                <span className="text-sm text-muted-foreground">
                  排序: {category.sort}
                </span>
                <Switch
                  checked={category.status}
                  onCheckedChange={() => toggleCategoryStatus(category.id)}
                />
                <Dialog>
                  <DialogTrigger asChild>
                    <Button
                      variant="ghost"
                      size="icon"
                      className="h-8 w-8"
                      onClick={() => setEditingCategory(category)}
                    >
                      <Pencil className="h-4 w-4" />
                    </Button>
                  </DialogTrigger>
                  <DialogContent className="bg-card border-border">
                    <DialogHeader>
                      <DialogTitle>编辑分类</DialogTitle>
                      <DialogDescription>
                        修改分类 {category.name} 的信息
                      </DialogDescription>
                    </DialogHeader>
                    <div className="space-y-4 py-4">
                      <div className="space-y-2">
                        <Label>分类名称</Label>
                        <Input
                          defaultValue={category.name}
                          className="bg-secondary border-0"
                        />
                      </div>
                      <div className="space-y-2">
                        <Label>分类描述</Label>
                        <Input
                          defaultValue={category.description}
                          className="bg-secondary border-0"
                        />
                      </div>
                      <div className="space-y-2">
                        <Label>排序</Label>
                        <Input
                          type="number"
                          defaultValue={category.sort}
                          className="bg-secondary border-0"
                        />
                      </div>
                    </div>
                    <DialogFooter>
                      <Button variant="outline">取消</Button>
                      <Button>保存修改</Button>
                    </DialogFooter>
                  </DialogContent>
                </Dialog>
                <Button
                  variant="ghost"
                  size="icon"
                  className="h-8 w-8 text-destructive hover:text-destructive"
                  disabled
                  title="V1 后台暂不支持删除分类"
                >
                  <Trash2 className="h-4 w-4" />
                </Button>
              </div>
            </div>
          ))}
        </div>

        {/* Help Text */}
        <p className="text-center text-sm text-muted-foreground">
          {editingCategory
            ? `当前编辑：${editingCategory.name}（V1 后台暂不持久化）`
            : "拖拽左侧图标可调整分类排序"}
        </p>
      </div>
    </AdminLayout>
  )
}
