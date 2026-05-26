"use client"

import { useEffect, useMemo, useState } from "react"
import { toast } from "sonner"
import { AdminLayout } from "@/components/admin/admin-layout"
import { AdminHeader } from "@/components/admin/header"
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Label } from "@/components/ui/label"
import { Skeleton } from "@/components/ui/skeleton"
import { Textarea } from "@/components/ui/textarea"
import { ApiError } from "@/lib/api/http"
import { fetchSettings, updateSettings } from "@/lib/api/settings"
import { fetchAdminTools } from "@/lib/api/tools"
import type { ToolSummary } from "@/lib/api/types"
import { AlertTriangle, Bot, BrainCircuit, CheckCircle2, Database, RefreshCw, Save, Wrench } from "lucide-react"

const AGENT_SYSTEM_PROMPT_KEY = "agent.system_prompt"
const DEEP_AGENTS_SYSTEM_PROMPT_KEY = "agent.deep_agents_system_prompt"

const DEFAULT_AGENT_SYSTEM_PROMPT = `你是 AI 工具市场的云代理。你的任务是理解用户需求，基于平台中可用的 AI 工具进行推荐、参数收集和必要时调用工具。

重要边界：
1. 你当前使用的 Agent 模型只负责理解、规划、对话和工具编排。
2. 每个 AI 工具会使用它在后台工具配置中绑定的模型、模板和执行器；不要把 Agent 模型误认为工具执行模型。
3. 当用户只是咨询时，直接回答；当用户需要生成图片、视频、语音、文案、标题、评论分析等结果时，优先从可用工具列表中选择最合适的工具。
4. 如果缺少工具必填参数，先用自然语言追问；不要编造参数。
5. 回答要简洁、可执行，必要时说明你将使用哪个工具。`

const DEFAULT_DEEP_AGENTS_SYSTEM_PROMPT = `你是 AI 工具市场的工作区 Agent。你可以结合会话历史、工作区记忆、文件上下文和可用工具来规划并完成任务。
保持步骤清晰，优先使用平台工具完成用户明确要求的生成或分析任务，并在最终答案中给出清晰结果。`

function errorMessage(error: unknown, fallback: string) {
  return error instanceof ApiError ? error.message : fallback
}

export default function PromptsPage() {
  const [agentPrompt, setAgentPrompt] = useState(DEFAULT_AGENT_SYSTEM_PROMPT)
  const [deepAgentsPrompt, setDeepAgentsPrompt] = useState(DEFAULT_DEEP_AGENTS_SYSTEM_PROMPT)
  const [tools, setTools] = useState<ToolSummary[]>([])
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function loadConfig() {
    setLoading(true)
    setError(null)
    try {
      const [settings, toolPage] = await Promise.all([
        fetchSettings(),
        fetchAdminTools({ status: "ONLINE", pageNo: 1, pageSize: 1000 }),
      ])
      setAgentPrompt(settings[AGENT_SYSTEM_PROMPT_KEY] || DEFAULT_AGENT_SYSTEM_PROMPT)
      setDeepAgentsPrompt(settings[DEEP_AGENTS_SYSTEM_PROMPT_KEY] || DEFAULT_DEEP_AGENTS_SYSTEM_PROMPT)
      setTools(toolPage.list || [])
    } catch (err) {
      setError(errorMessage(err, "加载 Agent 配置失败"))
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    loadConfig()
  }, [])

  async function saveConfig() {
    setSaving(true)
    setError(null)
    const toastId = toast.loading("正在保存 Agent 配置...")
    try {
      await updateSettings({
        [AGENT_SYSTEM_PROMPT_KEY]: agentPrompt.trim() || DEFAULT_AGENT_SYSTEM_PROMPT,
        [DEEP_AGENTS_SYSTEM_PROMPT_KEY]: deepAgentsPrompt.trim() || DEFAULT_DEEP_AGENTS_SYSTEM_PROMPT,
      })
      toast.success("Agent 配置已保存", { id: toastId })
    } catch (err) {
      const message = errorMessage(err, "保存 Agent 配置失败")
      setError(message)
      toast.error("保存失败", { id: toastId, description: message })
    } finally {
      setSaving(false)
    }
  }

  const modelBoundToolCount = useMemo(
    () => tools.filter((tool) => tool.modelConfigId != null || tool.modelName).length,
    [tools],
  )
  const previewTools = tools.slice(0, 12)

  return (
    <AdminLayout>
      <AdminHeader
        title="Agent 配置"
        description="配置 Agent 的系统提示词，并检查它能读取的在线 AI 工具"
      />

      <main className="space-y-6 p-6">
        {error && (
          <Alert variant="destructive">
            <AlertTriangle />
            <AlertTitle>配置加载异常</AlertTitle>
            <AlertDescription>{error}</AlertDescription>
          </Alert>
        )}

        <section className="grid gap-4 md:grid-cols-3">
          <Card className="rounded-lg">
            <CardHeader className="flex-row items-center gap-3 space-y-0">
              <div className="rounded-md border bg-muted p-2">
                <Bot className="h-5 w-5 text-primary" />
              </div>
              <div>
                <CardTitle>Agent 模型</CardTitle>
                <CardDescription>由后台模型表的“作为 Agent 模型”决定前台可选项</CardDescription>
              </div>
            </CardHeader>
          </Card>

          <Card className="rounded-lg">
            <CardHeader className="flex-row items-center gap-3 space-y-0">
              <div className="rounded-md border bg-muted p-2">
                <Wrench className="h-5 w-5 text-primary" />
              </div>
              <div>
                <CardTitle>AI 工具</CardTitle>
                <CardDescription>Agent 读取在线工具，工具执行使用各自绑定模型</CardDescription>
              </div>
            </CardHeader>
          </Card>

          <Card className="rounded-lg">
            <CardHeader className="flex-row items-center gap-3 space-y-0">
              <div className="rounded-md border bg-muted p-2">
                <Database className="h-5 w-5 text-primary" />
              </div>
              <div>
                <CardTitle>系统配置</CardTitle>
                <CardDescription>提示词保存到 system_settings，无需新增业务表</CardDescription>
              </div>
            </CardHeader>
          </Card>
        </section>

        <section className="grid gap-6 xl:grid-cols-[minmax(0,1fr)_380px]">
          <div className="space-y-6">
            <Card className="rounded-lg">
              <CardHeader>
                <div className="flex items-start justify-between gap-4">
                  <div>
                    <CardTitle className="flex items-center gap-2">
                      <Bot className="h-5 w-5" />
                      普通 Agent 系统提示词
                    </CardTitle>
                    <CardDescription>
                      用于常规对话、工具推荐和参数追问。平台会在运行时自动追加在线工具清单。
                    </CardDescription>
                  </div>
                  <Badge variant="secondary">{AGENT_SYSTEM_PROMPT_KEY}</Badge>
                </div>
              </CardHeader>
              <CardContent className="space-y-3">
                <Label htmlFor="agent-system-prompt">System Prompt</Label>
                {loading ? (
                  <Skeleton className="h-72 w-full" />
                ) : (
                  <Textarea
                    id="agent-system-prompt"
                    value={agentPrompt}
                    onChange={(event) => setAgentPrompt(event.target.value)}
                    className="min-h-72 resize-y font-mono text-sm leading-6"
                  />
                )}
              </CardContent>
            </Card>

            <Card className="rounded-lg">
              <CardHeader>
                <div className="flex items-start justify-between gap-4">
                  <div>
                    <CardTitle className="flex items-center gap-2">
                      <BrainCircuit className="h-5 w-5" />
                      Deep Agents 系统提示词
                    </CardTitle>
                    <CardDescription>
                      用于后续更复杂的工作区 Agent、文件上下文和记忆能力。
                    </CardDescription>
                  </div>
                  <Badge variant="secondary">{DEEP_AGENTS_SYSTEM_PROMPT_KEY}</Badge>
                </div>
              </CardHeader>
              <CardContent className="space-y-3">
                <Label htmlFor="deep-agents-system-prompt">System Prompt</Label>
                {loading ? (
                  <Skeleton className="h-44 w-full" />
                ) : (
                  <Textarea
                    id="deep-agents-system-prompt"
                    value={deepAgentsPrompt}
                    onChange={(event) => setDeepAgentsPrompt(event.target.value)}
                    className="min-h-44 resize-y font-mono text-sm leading-6"
                  />
                )}
              </CardContent>
            </Card>
          </div>

          <aside className="space-y-6">
            <Card className="rounded-lg">
              <CardHeader>
                <CardTitle>工具读取范围</CardTitle>
                <CardDescription>
                  当前 Agent 上下文会读取在线工具；单个工具的模型来自工具自身配置。
                </CardDescription>
              </CardHeader>
              <CardContent className="space-y-4">
                <div className="grid grid-cols-2 gap-3">
                  <div className="rounded-lg border bg-muted/40 p-3">
                    <div className="text-2xl font-semibold">{loading ? "-" : tools.length}</div>
                    <div className="text-xs text-muted-foreground">在线工具</div>
                  </div>
                  <div className="rounded-lg border bg-muted/40 p-3">
                    <div className="text-2xl font-semibold">{loading ? "-" : modelBoundToolCount}</div>
                    <div className="text-xs text-muted-foreground">已绑定模型</div>
                  </div>
                </div>

                <div className="space-y-2">
                  {loading ? (
                    Array.from({ length: 6 }).map((_, index) => (
                      <Skeleton key={index} className="h-10 w-full" />
                    ))
                  ) : previewTools.length ? (
                    previewTools.map((tool) => (
                      <div key={tool.id} className="rounded-lg border px-3 py-2">
                        <div className="flex items-center justify-between gap-2">
                          <span className="truncate text-sm font-medium">{tool.toolName}</span>
                          <Badge variant={tool.modelConfigId != null || tool.modelName ? "default" : "outline"}>
                            {tool.modelConfigId != null || tool.modelName ? "有模型" : "未绑定"}
                          </Badge>
                        </div>
                        <div className="mt-1 truncate text-xs text-muted-foreground">
                          {tool.toolCode}
                        </div>
                      </div>
                    ))
                  ) : (
                    <div className="rounded-lg border border-dashed p-6 text-center text-sm text-muted-foreground">
                      暂无在线工具
                    </div>
                  )}
                </div>
              </CardContent>
            </Card>

            <Alert>
              <CheckCircle2 />
              <AlertTitle>当前链路</AlertTitle>
              <AlertDescription>
                前台选择的是 Agent 推理模型；Agent 读取在线 AI 工具列表；工具真正执行时继续使用后台工具绑定的模型和参数。
              </AlertDescription>
            </Alert>
          </aside>
        </section>

        <div className="sticky bottom-4 z-20 flex justify-end gap-3">
          <Button variant="outline" onClick={loadConfig} disabled={loading || saving}>
            <RefreshCw className="mr-2 h-4 w-4" />
            刷新
          </Button>
          <Button onClick={saveConfig} disabled={loading || saving}>
            <Save className="mr-2 h-4 w-4" />
            {saving ? "保存中..." : "保存配置"}
          </Button>
        </div>
      </main>
    </AdminLayout>
  )
}
