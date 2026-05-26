"use client"

import { useCallback, useEffect, useMemo, useState } from "react"
import Link from "next/link"
import { Alert, AlertDescription } from "@/components/ui/alert"
import { ApiReferenceCard, type ToolApiReference } from "@/components/admin/api-reference-card"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { fetchAgentModelConfigs } from "@/lib/api/agent-model"
import { fetchModelProviders } from "@/lib/api/model-providers"
import type { ToolIntegrationApiCatalog } from "@/lib/api/tool-integration-api"
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
import type { AgentModelConfig, ModelProviderDescriptor } from "@/lib/api/types"
import {
  capabilityLabel,
  modelConfigSupportsCapability,
  resolvedModelCapabilities,
} from "@/lib/model-capabilities"
import { AlertCircle, CheckCircle2, ExternalLink, KeyRound, RefreshCw, Save, ServerCog } from "lucide-react"

/** 当前仅 PPT 插件走 ppt-workflow API；后续新插件可扩展 fetch/save 策略 */
const WORKFLOW_ADAPTERS: Record<
  string,
  {
    load: (toolId: number) => Promise<PptAdminWorkflowDetail>
    save: (toolId: number, workflow: PptWorkflow) => Promise<PptAdminWorkflowDetail>
    sync: (toolId: number) => Promise<PptAdminWorkflowDetail>
  }
> = {
  ppt: {
    load: fetchPptWorkflow,
    save: updatePptWorkflow,
    sync: syncPptEngineSettings,
  },
}

interface ToolIntegrationApiSectionProps {
  pluginId: string
  toolId: number
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
  return `${config.modelName} · ${config.provider} · ${key ? `Key: ${key}` : "未配置 API Key"}`
}

function apiReferenceFromField(
  catalog: ToolIntegrationApiCatalog,
  fieldKey: string,
  secrets: Record<string, string>,
  secretViews: PptAdminWorkflowDetail["engineSecretFields"],
): ToolApiReference | null {
  const def = catalog.engineApiFields.find((f) => f.key === fieldKey)
  if (!def) return null
  const view = secretViews?.find((v) => v.key === fieldKey)
  const configured = view?.configured ?? Boolean(secrets[fieldKey] || secrets[def.enginePayloadKey])
  return {
    title: def.label,
    provider: catalog.displayName,
    model: configured ? "已配置" : "未配置",
    endpoint: def.endpointHint || `引擎字段 ${def.enginePayloadKey}`,
    baseUrl: secrets[def.key] || secrets[def.enginePayloadKey] || view?.displayValue || "—",
    fields: [def.description, `同步字段：${def.enginePayloadKey}`],
    note: def.fieldType === "SECRET"
      ? "密钥保存在工具 workflow 配置中，保存并同步后写入 PPT 引擎。留空提交则保留原密钥。"
      : "保存并同步后写入 PPT 引擎 PUT /api/settings。",
    docUrl: def.docUrl || undefined,
  }
}

export function ToolIntegrationApiSection({ pluginId, toolId, onSaved }: ToolIntegrationApiSectionProps) {
  const adapter = WORKFLOW_ADAPTERS[pluginId]
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [syncing, setSyncing] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [detail, setDetail] = useState<PptAdminWorkflowDetail | null>(null)
  const [workflow, setWorkflow] = useState<PptWorkflow | null>(null)
  const [secretDraft, setSecretDraft] = useState<Record<string, string>>({})
  const [modelConfigs, setModelConfigs] = useState<AgentModelConfig[]>([])
  const [providerCatalog, setProviderCatalog] = useState<ModelProviderDescriptor[]>([])
  const [engineHealth, setEngineHealth] = useState<{ healthy: boolean; status: string } | null>(null)

  const catalog = detail?.apiCatalog ?? null

  const providerCapabilities = useMemo(() => {
    const map: Record<string, string[]> = {}
    for (const provider of providerCatalog) {
      map[provider.code.trim().toLowerCase()] = provider.capabilities || []
    }
    return map
  }, [providerCatalog])

  const modelsForCapability = useCallback(
    (capability: string) =>
      modelConfigs.filter(
        (c) => c.enabled !== false && modelConfigSupportsCapability(c, capability, providerCapabilities),
      ),
    [modelConfigs, providerCapabilities],
  )

  const load = useCallback(async () => {
    if (!adapter) {
      setError(`插件 ${pluginId} 尚未接入保存接口`)
      setLoading(false)
      return
    }
    setLoading(true)
    setError(null)
    try {
      const [workflowDetail, configs, providers, health] = await Promise.all([
        adapter.load(toolId),
        fetchAgentModelConfigs(),
        fetchModelProviders(),
        pluginId === "ppt" ? fetchPptEngineHealth().catch(() => ({ healthy: false, status: "UNKNOWN" })) : Promise.resolve(null),
      ])
      setDetail(workflowDetail)
      setWorkflow(workflowDetail.workflow)
      setSecretDraft({})
      setModelConfigs(configs)
      setProviderCatalog(providers)
      setEngineHealth(health)
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "加载工具 API 配置失败")
    } finally {
      setLoading(false)
    }
  }, [adapter, pluginId, toolId])

  useEffect(() => {
    void load()
  }, [load])

  function patchWorkflow(patch: Partial<PptWorkflow>) {
    setWorkflow((current) => (current ? { ...current, ...patch } : current))
  }

  function patchSecret(key: string, value: string) {
    setSecretDraft((prev) => ({ ...prev, [key]: value }))
  }

  function buildWorkflowPayload(): PptWorkflow | null {
    if (!workflow) return null
    const mergedSecrets = { ...(workflow.engineSecrets || {}) }
    for (const [key, value] of Object.entries(secretDraft)) {
      if (value.trim()) {
        mergedSecrets[key] = value.trim()
      }
    }
    return { ...workflow, engineSecrets: mergedSecrets }
  }

  async function handleSyncOnly() {
    if (!adapter) return
    setError(null)
    setNotice(null)
    setSyncing(true)
    try {
      const result = await adapter.sync(toolId)
      setDetail(result)
      setWorkflow(result.workflow)
      setSecretDraft({})
      setNotice(result.engineSyncMessage || (result.engineSynced ? "已同步到引擎" : "同步失败"))
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "同步引擎失败")
    } finally {
      setSyncing(false)
    }
  }

  async function handleSave(syncAfterSave: boolean) {
    const payload = buildWorkflowPayload()
    if (!payload || !adapter) return
    setError(null)
    setNotice(null)
    if (syncAfterSave) setSyncing(true)
    else setSaving(true)
    try {
      let result = await adapter.save(toolId, payload)
      if (syncAfterSave) {
        result = await adapter.sync(toolId)
      }
      setDetail(result)
      setWorkflow(result.workflow)
      setSecretDraft({})
      setNotice(
        result.engineSynced
          ? result.engineSyncMessage || "已保存并同步到引擎"
          : syncAfterSave
            ? result.engineSyncMessage || "已保存，但同步引擎失败"
            : "已保存 API 配置",
      )
      onSaved?.()
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "保存失败")
    } finally {
      setSaving(false)
      setSyncing(false)
    }
  }

  if (!adapter) {
    return <p className="text-sm text-destructive">未知插件：{pluginId}</p>
  }

  if (loading) {
    return <p className="text-sm text-muted-foreground">正在加载 API 配置目录…</p>
  }

  if (!workflow || !catalog) {
    return (
      <Alert variant="destructive">
        <AlertCircle className="h-4 w-4" />
        <AlertDescription>{error || "无法加载工作流或 API 目录"}</AlertDescription>
      </Alert>
    )
  }

  const secrets = workflow.engineSecrets || {}
  const apiCards: ToolApiReference[] = []

  if (detail?.textModel) {
    const ref = buildPptTextModelReference(detail.textModel)
    if (ref) apiCards.push(ref)
  }
  if (detail?.imageModel) {
    const ref = buildPptImageModelReference(detail.imageModel)
    if (ref) apiCards.push(ref)
  }
  for (const field of catalog.engineApiFields) {
    const ref = apiReferenceFromField(catalog, field.key, secrets, detail.engineSecretFields)
    if (ref) apiCards.push(ref)
  }

  return (
    <div className="space-y-5">
      <p className="text-xs text-muted-foreground">
        {catalog.displayName} · {catalog.syncHint}。字段目录由后端
        <code className="mx-1 rounded bg-muted px-1">integration-plugins/{pluginId}/api-catalog</code>
        下发，新增 API 只需扩展目录，无需改页面结构。
      </p>

      {pluginId === "ppt" ? (
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
      ) : null}

      {error ? (
        <Alert variant="destructive">
          <AlertDescription>{error}</AlertDescription>
        </Alert>
      ) : null}
      {notice ? (
        <Alert>
          <CheckCircle2 className="h-4 w-4" />
          <AlertDescription>{notice}</AlertDescription>
        </Alert>
      ) : null}

      <div className="space-y-4 rounded-lg border border-border bg-secondary/20 p-4">
        <p className="text-sm font-medium text-card-foreground">配置项</p>

        {catalog.modelBindings.map((binding) => {
          const models = modelsForCapability(binding.requiredCapability)
          const bindingValue =
            binding.bindingKey === "textModelConfigId"
              ? workflow.textModelConfigId
              : binding.bindingKey === "imageModelConfigId"
                ? workflow.imageModelConfigId
                : null
          const summary =
            binding.bindingKey === "textModelConfigId"
              ? detail.textModel
              : binding.bindingKey === "imageModelConfigId"
                ? detail.imageModel
                : null

          return (
            <div key={binding.bindingKey} className="space-y-2 border-t border-border/60 pt-3 first:border-0 first:pt-0">
              <Label>
                {binding.label}
                <span className="ml-2 text-xs font-normal text-muted-foreground">（{capabilityLabel(binding.requiredCapability)}）</span>
              </Label>
              <p className="text-xs text-muted-foreground">{binding.description}</p>
              <Select
                value={bindingValue ? String(bindingValue) : "__none__"}
                onValueChange={(value) => {
                  const id = value === "__none__" ? undefined : Number(value)
                  if (binding.bindingKey === "textModelConfigId") {
                    patchWorkflow({ textModelConfigId: id })
                  } else if (binding.bindingKey === "imageModelConfigId") {
                    patchWorkflow({ imageModelConfigId: id })
                  }
                }}
              >
                <SelectTrigger>
                  <SelectValue placeholder={`选择${binding.label}`} />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="__none__">不绑定</SelectItem>
                  {models.map((config) => (
                    <SelectItem key={config.id} value={String(config.id)}>
                      {modelOptionLabel(config)}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
              <p className="text-xs text-muted-foreground">{modelSummaryLine(summary)}</p>
              {models.length === 0 ? (
                <p className="text-xs text-amber-600">
                  暂无匹配模型。
                  <Link href="/settings" className="ml-1 underline">
                    去系统配置添加
                    <ExternalLink className="ml-0.5 inline h-3 w-3" />
                  </Link>
                </p>
              ) : null}
            </div>
          )
        })}

        {catalog.engineApiFields.map((field) => {
          const view = detail.engineSecretFields?.find((v) => v.key === field.key)
          const isSecret = field.fieldType === "SECRET"
          const placeholder = isSecret
            ? view?.configured
              ? `已配置（${view.displayValue || "****"}），留空不修改`
              : "填写 API Key / Token"
            : field.fieldType === "URL"
              ? "https://..."
              : ""

          return (
            <div key={field.key} className="space-y-2 border-t border-border/60 pt-3">
              <Label>{field.label}</Label>
              <p className="text-xs text-muted-foreground">{field.description}</p>
              <Input
                type={isSecret ? "password" : "text"}
                value={secretDraft[field.key] ?? (isSecret ? "" : secrets[field.key] || secrets[field.enginePayloadKey] || "")}
                placeholder={placeholder}
                onChange={(event) => patchSecret(field.key, event.target.value)}
                autoComplete="off"
              />
              {field.endpointHint ? (
                <p className="font-mono text-[11px] text-muted-foreground">{field.endpointHint}</p>
              ) : null}
            </div>
          )
        })}
      </div>

      <div className="flex flex-wrap items-center gap-2 rounded-lg border border-dashed border-border bg-secondary/20 px-4 py-3 text-xs text-muted-foreground">
        <KeyRound className="h-3.5 w-3.5 shrink-0" />
        <span>
          大模型 API Key 在「系统配置 → 大模型接入」维护；MinerU / 百度等在本栏填写后随「保存并同步」写入引擎。
        </span>
      </div>

      <div className="flex flex-wrap gap-2">
        <Button type="button" disabled={saving || syncing} onClick={() => void handleSave(false)} className="gap-2">
          <Save className="h-4 w-4" />
          {saving ? "保存中…" : "保存配置"}
        </Button>
        <Button type="button" disabled={saving || syncing} onClick={() => void handleSave(true)} className="gap-2">
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
          {apiCards.map((ref) => (
            <ApiReferenceCard key={ref.title} reference={ref} description="按目录动态生成。" />
          ))}
        </div>
      </div>
    </div>
  )
}
