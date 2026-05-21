"use client"

import { useEffect, useMemo, useState } from "react"
import { AdminLayout } from "@/components/admin/admin-layout"
import { AdminHeader } from "@/components/admin/header"
import { Input } from "@/components/ui/input"
import { ApiError } from "@/lib/api/http"
import { fetchAdminTools } from "@/lib/api/tools"
import type { ToolSummary } from "@/lib/api/types"
import { FileText, Search, Tags } from "lucide-react"

interface ModalityCategory {
  code: string
  name: string
  count: number
  status: string
}

const modalityLabels: Record<string, string> = {
  TEXT: "文本生成",
  IMAGE: "图片生成",
  AUDIO: "音频生成",
  VIDEO: "视频生成",
  JSON: "结构化数据",
  FILE: "文件生成",
  MULTIMODAL: "多模态生成",
}

const modalityOrder = ["TEXT", "IMAGE", "AUDIO", "VIDEO", "JSON", "FILE", "MULTIMODAL"]

function normalizeModality(value?: string | null) {
  return (value || "TEXT").trim().toUpperCase()
}

export default function CategoriesPage() {
  const [categories, setCategories] = useState<ModalityCategory[]>([])
  const [searchQuery, setSearchQuery] = useState("")
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  async function loadAll() {
    setLoading(true)
    setError(null)
    try {
      const toolsResp = await fetchAdminTools().catch(() => ({ list: [] as ToolSummary[], total: 0 }))
      const counts: Record<string, number> = {}
      toolsResp.list.forEach((tool) => {
        const code = normalizeModality(tool.outputModality)
        counts[code] = (counts[code] ?? 0) + 1
      })
      setCategories(
        Object.entries(counts)
          .sort(([a], [b]) => {
            const ia = modalityOrder.indexOf(a)
            const ib = modalityOrder.indexOf(b)
            return (ia === -1 ? 99 : ia) - (ib === -1 ? 99 : ib)
          })
          .map(([code, count]) => ({
            code,
            name: modalityLabels[code] || code,
            count,
            status: "VIRTUAL",
          })),
      )
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "加载生成类型失败")
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    loadAll()
  }, [])

  const filtered = useMemo(() => {
    const keyword = searchQuery.trim().toLowerCase()
    if (!keyword) return categories
    return categories.filter((category) =>
      [category.code, category.name, category.status].some((value) =>
        value.toLowerCase().includes(keyword),
      ),
    )
  }, [categories, searchQuery])

  const description = error
    ? `联调异常：${error}`
    : loading
      ? "正在统计工具生成类型"
      : "临时按工具输出模态汇总分类，数据库分类暂不作为前台展示依据"

  return (
    <AdminLayout>
      <AdminHeader title="分类管理" description={description} />

      <div className="p-6 space-y-6">
        <div className="flex items-center justify-between gap-4">
          <div className="relative max-w-md flex-1">
            <Search className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
            <Input
              value={searchQuery}
              onChange={(event) => setSearchQuery(event.target.value)}
              placeholder="搜索生成类型或编码"
              className="pl-9"
            />
          </div>
        </div>

        <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
          {filtered.map((category) => (
            <article key={category.code} className="rounded-lg border border-border bg-card p-5">
              <div className="flex items-start justify-between gap-3">
                <div className="flex items-center gap-3">
                  <div className="flex h-10 w-10 items-center justify-center rounded-md bg-primary/10 text-primary">
                    <Tags className="h-5 w-5" />
                  </div>
                  <div>
                    <h2 className="font-semibold">{category.name}</h2>
                    <p className="text-xs text-muted-foreground">{category.code}</p>
                  </div>
                </div>
              </div>
              <div className="mt-5 grid grid-cols-3 gap-3 text-sm">
                <div>
                  <p className="text-muted-foreground">工具数</p>
                  <p className="mt-1 font-medium">{category.count}</p>
                </div>
                <div>
                  <p className="text-muted-foreground">来源</p>
                  <p className="mt-1 font-medium">输出模态</p>
                </div>
                <div>
                  <p className="text-muted-foreground">状态</p>
                  <p className="mt-1 font-medium">{category.status}</p>
                </div>
              </div>
              <div className="mt-5 flex items-center justify-between border-t border-border pt-4">
                <div className="flex items-center gap-2 text-xs text-muted-foreground">
                  <FileText className="h-4 w-4" />
                  <span>前台展示分类</span>
                </div>
              </div>
            </article>
          ))}
        </div>
      </div>
    </AdminLayout>
  )
}
