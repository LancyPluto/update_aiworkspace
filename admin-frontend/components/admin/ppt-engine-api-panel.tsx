"use client"

import { useCallback, useEffect, useMemo, useState } from "react"
import Link from "next/link"
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert"
import { ApiReferenceCard } from "@/components/admin/api-reference-card"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Label } from "@/components/ui/label"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { fetchAgentModelConfigs } from "@/lib/api/agent-model"
import { fetchModelProviders } from "@/lib/api/model-providers"
import {
  fetchPptEngineHealth,
  fetchPptWorkflow,
  syncPptEngineSettings,
  updatePptWorkflow,
  type PptAdminWorkflowDetail,
  type PptWorkflow,
} from "@/lib/api/ppt-workflow"
import { ApiError } from "@/lib/api/http"
import { buildPptImageModelReference, buildPptTextModelReference } from "@/lib/ppt-model-api-reference"
import { PPT_EXTERNAL_API_REFERENCE_LIST } from "@/lib/ppt-tool-api-docs"
import type { AgentModelConfig, ModelProviderDescriptor } from "@/lib/api/types"
import {
  capabilityLabel,
  modelConfigSupportsCapability,
  resolvedModelCapabilities,
} from "@/lib/model-capabilities"
import { AlertCircle, CheckCircle2, ExternalLink, KeyRound, RefreshCw, Save, ServerCog } from "lucide-react"

interface PptEngineApiPanelProps {
  toolId: number
  toolName: string
  /** 嵌入工具编辑弹窗时使用更紧凑的引导文案 */
  embedded?: boolean
  onSaved?: () => void
}

function modelOptionLabel(config: AgentModelConfig): string {
  const caps = resolvedModelCapabilities(config)
  const capText = caps.length > 0 ? caps.map(capabilityLabel).join("、") : "未标注能力"
  return `${config.displayName || config.modelName} · ${config.provider} (${capText})`
}

function modelSummaryLine(config: AgentModelConfig | null | undefined): string {
  if (!config) return "未选择"
  const key = config.apiKeyMasked?.trim()
  const keyStatus = key ? `Key: ${key}` : "未配置 API Key"
  return `${config.modelName} · ${config.provider} · ${keyStatus}`
}

export function PptEngineApiPanel({ toolId, toolName, embedded = false, onSaved }: PptEngineApiPanelProps) {
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [syncing, setSyncing] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [detail, setDetail] = useState<PptAdminWorkflowDetail | null>(null)
  const [workflow, setWorkflow] = useState<PptWorkflow | null>(null)
  const [modelConfigs, setModelConfigs] = useState<AgentModelConfig[]>([])
  const [providerCatalog, setProviderCatalog] = useState<ModelProviderDescriptor[]>([])
  const [engineHealth, setEngineHealth] = useState<{ healthy: boolean; status: string } | null>(null)

  const providerCapabilities = useMemo(() => {
    const map: Record<string, string[]> = {}
    for (const provider of providerCatalog) {
      map[provider.code.trim().toLowerCase()] = provider.capabilities || []
    }
    return map
  }, [providerCatalog])

  const textModels = useMemo(
    () =>
      modelConfigs.filter(
        (c) => c.enabled !== false && modelConfigSupportsCapability(c, "TEXT_GENERATION", providerCapabilities),
      ),
    [modelConfigs, providerCapabilities],
  )

  const imageModels = useMemo(
    () =>
      modelConfigs.filter(
        (c) => c.enabled !== false && modelConfigSupportsCapability(c, "IMAGE_GENERATION", providerCapabilities),
      ),
    [modelConfigs, providerCapabilities],
  )

  const textRef = useMemo(() => buildPptTextModelReference(detail?.textModel ?? null), [detail?.textModel])
  const imageRef = useMemo(() => buildPptImageModelReference(detail?.imageModel ?? null), [detail?.imageModel])

  const load = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const [workflowDetail, configs, providers, health] = await Promise.all([
        fetchPptWorkflow(toolId),
        fetchAgentModelConfigs(),
        fetchModelProviders(),
        fetchPptEngineHealth().catch(() => ({ healthy: false, status: "UNKNOWN" })),
      ])
      setDetail(workflowDetail)
      setWorkflow(workflowDetail.workflow)
      setModelConfigs(configs)
      setProviderCatalog(providers)
      setEngineHealth(health)
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "加载 PPT 引擎配置失败")
    } finally {
      setLoading(false)
    }
  }, [toolId])

  useEffect(() => {
    void load()
  }, [load])

  async function handleSave(syncAfterSave: boolean) {
    if (!workflow) return
    if (!workflow.textModelConfigId && !workflow.imageModelConfigId) {
      setError("请至少选择文本模型或生图模型之一")
      return
    }
    setError(null)
    setNotice(null)
    if (syncAfterSave) setSyncing(true)
    else setSaving(true)
    try {
      let result = await updatePptWorkflow(toolId, workflow)
      if (syncAfterSave) {
        result = await syncPptEngineSettings(toolId)
      }
      setDetail(result)
      setWorkflow(result.workflow)
      setNotice(
        result.engineSynced
          ? result.engineSyncMessage || "已保存并同步到 PPT 引擎"
          : syncAfterSave
            ? result.engineSyncMessage || "已保存，但同步引擎失败"
            : "已保存 PPT 模型绑定",
      )
      onSaved?.()
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "保存失败")
    } finally {
      setSaving(false)
      setSyncing(false)
    }
  }

  async function handleSyncOnly() {
    setError(null)
    setNotice(null)
    setSyncing(true)
    try {
      const result = await syncPptEngineSettings(toolId)
      setDetail(result)
      setWorkflow(result.workflow)
      setNotice(result.engineSyncMessage || (result.engineSynced ? "已同步到 PPT 引擎" : "同步失败"))
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "同步引擎失败")
    } finally {
      setSyncing(false)
    }
  }

  if (loading) {
    return <p className="text-sm text-muted-foreground">正在加载 PPT 引擎 API 配置…</p>
  }

  if (!workflow) {
    return (
      <Alert variant="destructive">
        <AlertCircle className="h-4 w-4" />
        <AlertTitle>无法加载工作流</AlertTitle>
        <AlertDescription>{error || "请确认该工具已配置 ppt-workflow 集成块。"}</AlertDescription>
      </Alert>
    )
  }

  return (
    <div className="space-y-5">
      {!embedded ? (
        <div className="rounded-lg border border-border bg-muted/30 p-4 text-sm">
          <p className="font-medium text-card-foreground">{toolName}</p>
          <p className="mt-1 text-muted-foreground">
            PPT 生成通过 banana-slides 引擎：除绑定并<strong>同步到引擎</strong>的文本 / 文生图模型外，翻新与导出等步骤还依赖
            <strong> MinerU（PDF 解析）</strong>与<strong>百度高精度 OCR</strong>等，请在引擎侧配置对应密钥（见最下方 API 说明卡片）。
          </p>
        </div>
      ) : null}

      <div className="flex flex-wrap items-center gap-3 rounded-lg border border-border px-4 py-3">
        <div className="flex items-center gap-2 text-sm">
          <ServerCog className="h-4 w-4 text-muted-foreground" />
          <span className="text-muted-foreground">PPT 引擎</span>
          {engineHealth?.healthy ? (
            <Badge variant="outline" className="gap-1 border-emerald-500/40 text-emerald-600">
              <CheckCircle2 className="h-3 w-3" />
              {engineHealth.status}
            </Badge>
          ) : (
            <Badge variant="destructive" className="gap-1">
              <AlertCircle className="h-3 w-3" />
              {engineHealth?.status || "不可达"}
            </Badge>
          )}
        </div>
        <Button type="button" variant="ghost" size="sm" className="gap-1" onClick={() => void load()}>
          <RefreshCw className="h-3.5 w-3.5" />
          刷新
        </Button>
      </div>

      {error ? (
        <Alert variant="destructive">
          <AlertCircle className="h-4 w-4" />
          <AlertDescription>{error}</AlertDescription>
        </Alert>
      ) : null}

      {notice ? (
        <Alert>
          <CheckCircle2 className="h-4 w-4" />
          <AlertDescription>{notice}</AlertDescription>
        </Alert>
      ) : null}

      <div className="grid gap-5 md:grid-cols-2">
        <div className="space-y-2 rounded-lg border border-border p-4">
          <Label>文本模型 API（大纲 / 描述）</Label>
          <Select
            value={workflow.textModelConfigId ? String(workflow.textModelConfigId) : "__none__"}
            onValueChange={(value) =>
              setWorkflow((current) =>
                current
                  ? {
                      ...current,
                      textModelConfigId: value === "__none__" ? undefined : Number(value),
                    }
                  : current,
              )
            }
          >
            <SelectTrigger>
              <SelectValue placeholder="选择文本生成模型" />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="__none__">不绑定</SelectItem>
              {textModels.map((config) => (
                <SelectItem key={config.id} value={String(config.id)}>
                  {modelOptionLabel(config)}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
          <p className="text-xs text-muted-foreground">{modelSummaryLine(detail?.textModel)}</p>
          {textModels.length === 0 ? (
            <p className="text-xs text-amber-600">
              暂无启用且标注「文本生成」能力的模型。
              <Link href="/settings" className="ml-1 inline-flex items-center gap-0.5 underline">
                去系统配置添加
                <ExternalLink className="h-3 w-3" />
              </Link>
            </p>
          ) : null}
        </div>

        <div className="space-y-2 rounded-lg border border-border p-4">
          <Label>生图模型 API（幻灯片配图）</Label>
          <Select
            value={workflow.imageModelConfigId ? String(workflow.imageModelConfigId) : "__none__"}
            onValueChange={(value) =>
              setWorkflow((current) =>
                current
                  ? {
                      ...current,
                      imageModelConfigId: value === "__none__" ? undefined : Number(value),
                    }
                  : current,
              )
            }
          >
            <SelectTrigger>
              <SelectValue placeholder="选择文生图模型" />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="__none__">不绑定</SelectItem>
              {imageModels.map((config) => (
                <SelectItem key={config.id} value={String(config.id)}>
                  {modelOptionLabel(config)}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
          <p className="text-xs text-muted-foreground">{modelSummaryLine(detail?.imageModel)}</p>
          {imageModels.length === 0 ? (
            <p className="text-xs text-amber-600">
              暂无启用且标注「文生图」能力的模型（如 volcengine_images、siliconflow_images）。
              <Link href="/settings" className="ml-1 inline-flex items-center gap-0.5 underline">
                去系统配置添加
                <ExternalLink className="h-3 w-3" />
              </Link>
            </p>
          ) : null}
        </div>
      </div>

      <div className="flex flex-wrap items-center gap-2 rounded-lg border border-dashed border-border bg-secondary/20 px-4 py-3 text-xs text-muted-foreground">
        <KeyRound className="h-3.5 w-3.5 shrink-0" />
        <span>
          文本 / 生图 API Key 在「系统配置 → 大模型接入」中维护；保存并同步后写入 banana-slides
          <code className="mx-1 rounded bg-muted px-1">PUT /api/settings</code>。MinerU、百度 OCR 等请在引擎实例中单独配置。
        </span>
      </div>

      <div className="flex flex-wrap gap-2">
        <Button type="button" disabled={saving || syncing} onClick={() => void handleSave(false)} className="gap-2">
          <Save className="h-4 w-4" />
          {saving ? "保存中…" : "保存绑定"}
        </Button>
        <Button
          type="button"
          variant="default"
          disabled={saving || syncing}
          onClick={() => void handleSave(true)}
          className="gap-2"
        >
          <RefreshCw className={`h-4 w-4 ${syncing ? "animate-spin" : ""}`} />
          {syncing ? "同步中…" : "保存并同步引擎"}
        </Button>
        <Button type="button" variant="outline" disabled={syncing} onClick={() => void handleSyncOnly()} className="gap-2">
          <ServerCog className="h-4 w-4" />
          仅同步引擎
        </Button>
      </div>

      <div className="border-t border-border pt-5">
        <h4 className="mb-2 text-sm font-semibold text-card-foreground">本工具涉及的外部 API</h4>
        <div className="grid gap-3 md:grid-cols-2">
          {textRef ? <ApiReferenceCard reference={textRef} description="当前绑定的文本模型（超市 → 引擎）。" /> : null}
          {imageRef ? <ApiReferenceCard reference={imageRef} description="当前绑定的生图模型（超市 → 引擎）。" /> : null}
          {PPT_EXTERNAL_API_REFERENCE_LIST.map((ref) => (
            <ApiReferenceCard key={ref.title} reference={ref} description="在 banana-slides 引擎内使用。" />
          ))}
        </div>
        {!textRef && !imageRef ? (
          <p className="mt-2 text-xs text-amber-700">
            尚未绑定文本或生图模型；请先完成上方模型选择并保存，再对照下方卡片核对各 API。
          </p>
        ) : null}
      </div>
    </div>
  )
}
