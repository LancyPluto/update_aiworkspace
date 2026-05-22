"use client"

import { useEffect, useState } from "react"
import Link from "next/link"
import { useParams, useRouter } from "next/navigation"
import { ArrowLeft, RefreshCw, Workflow } from "lucide-react"
import { AdminHeader } from "@/components/admin/header"
import { AdminLayout } from "@/components/admin/admin-layout"
import { WorkflowCanvas } from "@/components/admin/workflow"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { fetchAgentModelConfigs } from "@/lib/api/agent-model"
import { ApiError } from "@/lib/api/http"
import { fetchAdminTools } from "@/lib/api/tools"
import type { AgentModelConfig, ToolSummary } from "@/lib/api/types"

export default function ToolWorkflowPage() {
  const params = useParams<{ toolId: string }>()
  const router = useRouter()
  const toolId = Number(params.toolId)

  const [tool, setTool] = useState<ToolSummary | null>(null)
  const [modelConfigs, setModelConfigs] = useState<AgentModelConfig[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (!Number.isFinite(toolId)) {
      setError("工具 ID 无效")
      setLoading(false)
      return
    }

    setLoading(true)
    setError(null)

    Promise.all([
      fetchAdminTools(),
      fetchAgentModelConfigs().catch(() => [] as AgentModelConfig[]),
    ])
      .then(([toolsResp, configs]) => {
        const current = toolsResp.list.find((item) => item.id === toolId)
        if (!current) {
          setTool(null)
          setError("没有找到对应的 AI 工具")
        } else {
          setTool(current)
        }
        setModelConfigs(configs.filter((config) => config.enabled !== false))
      })
      .catch((err) => {
        setError(err instanceof ApiError ? err.message : "加载失败")
      })
      .finally(() => setLoading(false))
  }, [toolId])

  const headerDescription = loading
    ? "正在加载工具工作流..."
    : error
      ? `加载失败：${error}`
      : tool
        ? `${tool.toolCode} / ${tool.executionHandler || tool.toolType || "未配置执行器"}`
        : "工具工作流"

  return (
    <AdminLayout>
      <AdminHeader title="AI 工具工作流" description={headerDescription} />

      <div className="flex h-[calc(100vh-100px)] flex-col p-4">
        <div className="mb-3 flex shrink-0 flex-wrap items-center justify-between gap-3">
          <div className="flex items-center gap-3">
            <Button
              type="button"
              variant="outline"
              size="sm"
              className="gap-2"
              onClick={() => router.push("/tools")}
            >
              <ArrowLeft className="h-4 w-4" />
              返回工具管理
            </Button>
            {tool ? (
              <div className="flex flex-wrap items-center gap-2">
                <Badge variant="secondary">{tool.toolName}</Badge>
                <Badge variant="outline">
                  {tool.inputModality || "输入"} {"->"} {tool.outputModality || "输出"}
                </Badge>
              </div>
            ) : null}
          </div>

          <div className="flex items-center gap-2">
            <Button asChild variant="outline" size="sm" className="gap-2">
              <Link href="/tools">
                <Workflow className="h-4 w-4" />
                工具列表
              </Link>
            </Button>
            <Button
              type="button"
              variant="outline"
              size="sm"
              className="gap-2"
              onClick={() => window.location.reload()}
              disabled={loading}
            >
              <RefreshCw className="h-4 w-4" />
              刷新
            </Button>
          </div>
        </div>

        {loading ? (
          <div className="flex min-h-[420px] items-center justify-center rounded-lg border border-border bg-card text-sm text-muted-foreground">
            正在加载工作流画布...
          </div>
        ) : error || !tool ? (
          <div className="flex min-h-[420px] flex-col items-center justify-center gap-3 rounded-lg border border-border bg-card text-sm text-muted-foreground">
            <p>{error || "工作流不存在"}</p>
            <Button asChild variant="outline" size="sm">
              <Link href="/tools">回到 AI 工具管理</Link>
            </Button>
          </div>
        ) : (
          <div className="min-h-0 flex-1">
            <WorkflowCanvas
              key={tool.id}
              toolId={tool.id}
              toolName={tool.toolName}
              tool={tool}
              modelConfigs={modelConfigs}
              variant="workspace"
            />
          </div>
        )}
      </div>
    </AdminLayout>
  )
}
