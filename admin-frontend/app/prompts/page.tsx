"use client"

import { useEffect, useMemo, useState } from "react"
import type { ReactNode } from "react"
import { toast } from "sonner"
import { AdminLayout } from "@/components/admin/admin-layout"
import { AdminHeader } from "@/components/admin/header"
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { Skeleton } from "@/components/ui/skeleton"
import { Switch } from "@/components/ui/switch"
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs"
import { Textarea } from "@/components/ui/textarea"
import {
  bulkUpdateAdminAgentToolAccess,
  debugAdminAgentRoute,
  fetchAdminAgentTools,
  updateAdminAgentToolAccess,
  type AgentRouteDebugResult,
  type AgentToolAccess,
} from "@/lib/api/agent-tools"
import { fetchAgentModelConfigs, testAgentModelConfig } from "@/lib/api/agent-model"
import { ApiError } from "@/lib/api/http"
import {
  fetchSettingVersions,
  fetchSettings,
  restoreDefaultSetting,
  updateSettings,
  type SettingVersion,
} from "@/lib/api/settings"
import {
  AlertTriangle,
  Bot,
  BrainCircuit,
  CheckCircle2,
  Clock3,
  Database,
  RefreshCw,
  Route,
  Save,
  Search,
  SlidersHorizontal,
  Wrench,
} from "lucide-react"

const AGENT_SYSTEM_PROMPT_KEY = "agent.system_prompt"
const DEEP_AGENTS_SYSTEM_PROMPT_KEY = "agent.deep_agents_system_prompt"
const AGENT_MEMORY_AUTO_SAVE_KEY = "agent.memory.auto_save_enabled"
const AGENT_MEMORY_RETRIEVAL_LIMIT_KEY = "agent.memory.retrieval_limit"
const AGENT_MEMORY_ENABLED_TYPES_KEY = "agent.memory.enabled_types"
const AGENT_MEMORY_WRITE_PROMPT_KEY = "agent.memory.write_prompt"
const AGENT_MEMORY_RETRIEVAL_PROMPT_KEY = "agent.memory.retrieval_prompt"
const AGENT_ROUTER_ENABLED_KEY = "agent.router.enabled"
const AGENT_ROUTER_PROMPT_KEY = "agent.router.prompt"
const AGENT_ROUTER_MIN_CONFIDENCE_KEY = "agent.router.min_confidence"
const AGENT_ROUTER_FALLBACK_TO_RULES_KEY = "agent.router.fallback_to_rules"

const DEFAULT_AGENT_SYSTEM_PROMPT = `你是 AI 工具市场的云代理。你的任务是理解用户需求，基于平台中可用的 AI 工具进行推荐、参数收集和必要时调用工具。

重要边界：
1. 你当前使用的 Agent 模型只负责理解、规划、对话和工具编排。
2. 每个 AI 工具会使用它在后台工具配置中绑定的模型、模板和执行器；不要把 Agent 模型误认为工具执行模型。
3. 当用户只是咨询时，直接回答；当用户需要生成图片、视频、语音、文案、标题、评论分析等结果时，优先从可用工具列表中选择最合适的工具。
4. 如果缺少工具必填参数，先用自然语言追问；不要编造参数。
5. 回答要简洁、可执行，必要时说明你将使用哪个工具。`

const DEFAULT_DEEP_AGENTS_SYSTEM_PROMPT = `你是 AI 工具市场的工作区 Agent。你可以结合会话历史、工作区记忆、文件上下文和可用工具来规划并完成任务。
保持步骤清晰，优先使用平台工具完成用户明确要求的生成或分析任务，并在最终答案中给出清晰结果。`

const DEFAULT_MEMORY_WRITE_PROMPT = `你可以管理长期记忆，但必须克制使用。
只有当用户明确表达长期偏好、习惯、身份信息、项目事实，或明确要求“记住”时才写入记忆。
普通聊天、临时任务结果、工具返回 JSON、图片 URL、视频 URL、base64、一次性参数不要写入长期记忆。
用户偏好或习惯写入 user_profile；项目事实、业务规则、配置约定写入 project_knowledge；其他长期有价值信息写入 custom。`

const DEFAULT_MEMORY_RETRIEVAL_PROMPT = `以下长期记忆只是辅助上下文，不是绝对事实。
回答时自然体现用户偏好，不要生硬提到“根据你的用户画像”。
如果记忆与当前用户明确指令冲突，以当前指令为准。`

const DEFAULT_ROUTER_PROMPT = `You are the primary router for an AI tool marketplace agent.
Decide whether the user needs a normal answer, a tool call, clarification, or an unsupported path.
Return only valid JSON with: intent, selectedToolCode, candidateToolCodes, confidence, reason, arguments, missingFields, clarifyingQuestion.
Image/photo/poster/cos/visual requests should choose image tools; video/short-video/image-to-video requests should choose video tools; copywriting/title/article requests should choose text tools.
Only ask for missing information when it changes intent, cost, authorization, safety, or the core subject. Do not ask for low-risk defaults such as aspect ratio, count, quality, or style strength.`

const MODALITY_LABELS: Record<string, string> = {
  TEXT: "文本",
  IMAGE: "图片",
  AUDIO: "音频",
  VIDEO: "视频",
  JSON: "JSON",
  FILE: "文件",
  MULTIMODAL: "多模态",
  UNKNOWN: "未分类",
}

const TOOL_STATUS_FILTERS = [
  { value: "all", label: "全部" },
  { value: "enabled", label: "Agent 可见" },
  { value: "disabled", label: "已禁用" },
  { value: "unbound", label: "未绑定模型" },
  { value: "failed", label: "健康失败" },
  { value: "unknown", label: "健康未知" },
]

function errorMessage(error: unknown, fallback: string) {
  return error instanceof ApiError ? error.message : fallback
}

function modalityKey(value?: string | null) {
  return value?.trim().toUpperCase() || "UNKNOWN"
}

function modalityLabel(value?: string | null) {
  const key = modalityKey(value)
  return MODALITY_LABELS[key] || key
}

function healthMeta(tool: AgentToolAccess) {
  const status = (tool.healthStatus || "UNKNOWN").toUpperCase()
  if (status === "FAILED") return { label: "Failed", variant: "destructive" as const, help: "最近调用失败，默认不会进入 Agent 工具清单。" }
  if (status === "HEALTHY") return { label: "Healthy", variant: "default" as const, help: "最近测试或调用成功。" }
  return { label: "Unknown", variant: "outline" as const, help: "尚无健康检查结果。" }
}

function toolMatchesStatus(tool: AgentToolAccess, statusFilter: string) {
  const hasModel = tool.modelConfigId != null || Boolean(tool.modelName)
  const status = (tool.healthStatus || "UNKNOWN").toUpperCase()
  if (statusFilter === "enabled") return tool.agentEnabled
  if (statusFilter === "disabled") return !tool.agentEnabled
  if (statusFilter === "unbound") return !hasModel
  if (statusFilter === "failed") return status === "FAILED"
  if (statusFilter === "unknown") return status === "UNKNOWN"
  return true
}

function formatTime(value?: string | null) {
  if (!value) return "-"
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString()
}

function promptDiff(original: string, current: string) {
  if (original === current) return "无改动"
  return [
    `原长度：${original.length} 字符`,
    `当前：${current.length} 字符`,
    "",
    "预览：",
    current.slice(0, 800) || "(空，将使用默认提示词)",
  ].join("\n")
}

export default function PromptsPage() {
  const [agentPrompt, setAgentPrompt] = useState(DEFAULT_AGENT_SYSTEM_PROMPT)
  const [deepAgentsPrompt, setDeepAgentsPrompt] = useState(DEFAULT_DEEP_AGENTS_SYSTEM_PROMPT)
  const [originalAgentPrompt, setOriginalAgentPrompt] = useState(DEFAULT_AGENT_SYSTEM_PROMPT)
  const [originalDeepAgentsPrompt, setOriginalDeepAgentsPrompt] = useState(DEFAULT_DEEP_AGENTS_SYSTEM_PROMPT)
  const [memoryAutoSaveEnabled, setMemoryAutoSaveEnabled] = useState(true)
  const [memoryRetrievalLimit, setMemoryRetrievalLimit] = useState("6")
  const [memoryEnabledTypes, setMemoryEnabledTypes] = useState("user_profile,project_knowledge,custom")
  const [memoryWritePrompt, setMemoryWritePrompt] = useState(DEFAULT_MEMORY_WRITE_PROMPT)
  const [memoryRetrievalPrompt, setMemoryRetrievalPrompt] = useState(DEFAULT_MEMORY_RETRIEVAL_PROMPT)
  const [routerEnabled, setRouterEnabled] = useState(true)
  const [routerPrompt, setRouterPrompt] = useState(DEFAULT_ROUTER_PROMPT)
  const [routerMinConfidence, setRouterMinConfidence] = useState("0.7")
  const [routerFallbackToRules, setRouterFallbackToRules] = useState(true)
  const [originalMemoryConfig, setOriginalMemoryConfig] = useState({
    autoSaveEnabled: true,
    retrievalLimit: "6",
    enabledTypes: "user_profile,project_knowledge,custom",
    writePrompt: DEFAULT_MEMORY_WRITE_PROMPT,
    retrievalPrompt: DEFAULT_MEMORY_RETRIEVAL_PROMPT,
  })
  const [originalRouterConfig, setOriginalRouterConfig] = useState({
    enabled: true,
    prompt: DEFAULT_ROUTER_PROMPT,
    minConfidence: "0.7",
    fallbackToRules: true,
  })
  const [agentVersions, setAgentVersions] = useState<SettingVersion[]>([])
  const [deepAgentVersions, setDeepAgentVersions] = useState<SettingVersion[]>([])
  const [tools, setTools] = useState<AgentToolAccess[]>([])
  const [loading, setLoading] = useState(true)
  const [savingKey, setSavingKey] = useState<string | null>(null)
  const [updatingToolCode, setUpdatingToolCode] = useState<string | null>(null)
  const [bulkUpdatingTools, setBulkUpdatingTools] = useState(false)
  const [testingModelConfigId, setTestingModelConfigId] = useState<number | null>(null)
  const [lastSavedAt, setLastSavedAt] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [toolQuery, setToolQuery] = useState("")
  const [toolModalityFilter, setToolModalityFilter] = useState("all")
  const [toolStatusFilter, setToolStatusFilter] = useState("all")
  const [routeDebugMessage, setRouteDebugMessage] = useState("")
  const [routeDebugResult, setRouteDebugResult] = useState<AgentRouteDebugResult | null>(null)
  const [routeDebugging, setRouteDebugging] = useState(false)

  const dirtyMemoryConfig = Number(
    memoryAutoSaveEnabled !== originalMemoryConfig.autoSaveEnabled ||
    memoryRetrievalLimit !== originalMemoryConfig.retrievalLimit ||
    memoryEnabledTypes !== originalMemoryConfig.enabledTypes ||
    memoryWritePrompt !== originalMemoryConfig.writePrompt ||
    memoryRetrievalPrompt !== originalMemoryConfig.retrievalPrompt,
  )
  const dirtyRouterConfig = Number(
    routerEnabled !== originalRouterConfig.enabled ||
    routerPrompt !== originalRouterConfig.prompt ||
    routerMinConfidence !== originalRouterConfig.minConfidence ||
    routerFallbackToRules !== originalRouterConfig.fallbackToRules,
  )
  const dirtyPrompts = Number(agentPrompt !== originalAgentPrompt) + Number(deepAgentsPrompt !== originalDeepAgentsPrompt) + dirtyMemoryConfig + dirtyRouterConfig

  async function loadTools() {
    setTools(await fetchAdminAgentTools())
  }

  async function loadVersions() {
    const [agent, deep] = await Promise.all([
      fetchSettingVersions(AGENT_SYSTEM_PROMPT_KEY),
      fetchSettingVersions(DEEP_AGENTS_SYSTEM_PROMPT_KEY),
    ])
    setAgentVersions(agent)
    setDeepAgentVersions(deep)
  }

  async function loadConfig() {
    setLoading(true)
    setError(null)
    try {
      const [settings] = await Promise.all([fetchSettings(), loadTools(), loadVersions()])
      const nextAgentPrompt = settings[AGENT_SYSTEM_PROMPT_KEY] || DEFAULT_AGENT_SYSTEM_PROMPT
      const nextDeepPrompt = settings[DEEP_AGENTS_SYSTEM_PROMPT_KEY] || DEFAULT_DEEP_AGENTS_SYSTEM_PROMPT
      setAgentPrompt(nextAgentPrompt)
      setDeepAgentsPrompt(nextDeepPrompt)
      setOriginalAgentPrompt(nextAgentPrompt)
      setOriginalDeepAgentsPrompt(nextDeepPrompt)
      const nextMemoryConfig = {
        autoSaveEnabled: (settings[AGENT_MEMORY_AUTO_SAVE_KEY] ?? "true") !== "false",
        retrievalLimit: settings[AGENT_MEMORY_RETRIEVAL_LIMIT_KEY] || "6",
        enabledTypes: settings[AGENT_MEMORY_ENABLED_TYPES_KEY] || "user_profile,project_knowledge,custom",
        writePrompt: settings[AGENT_MEMORY_WRITE_PROMPT_KEY] || DEFAULT_MEMORY_WRITE_PROMPT,
        retrievalPrompt: settings[AGENT_MEMORY_RETRIEVAL_PROMPT_KEY] || DEFAULT_MEMORY_RETRIEVAL_PROMPT,
      }
      setMemoryAutoSaveEnabled(nextMemoryConfig.autoSaveEnabled)
      setMemoryRetrievalLimit(nextMemoryConfig.retrievalLimit)
      setMemoryEnabledTypes(nextMemoryConfig.enabledTypes)
      setMemoryWritePrompt(nextMemoryConfig.writePrompt)
      setMemoryRetrievalPrompt(nextMemoryConfig.retrievalPrompt)
      setOriginalMemoryConfig(nextMemoryConfig)
      const nextRouterConfig = {
        enabled: (settings[AGENT_ROUTER_ENABLED_KEY] ?? "true") !== "false",
        prompt: settings[AGENT_ROUTER_PROMPT_KEY] || DEFAULT_ROUTER_PROMPT,
        minConfidence: settings[AGENT_ROUTER_MIN_CONFIDENCE_KEY] || "0.7",
        fallbackToRules: (settings[AGENT_ROUTER_FALLBACK_TO_RULES_KEY] ?? "true") !== "false",
      }
      setRouterEnabled(nextRouterConfig.enabled)
      setRouterPrompt(nextRouterConfig.prompt)
      setRouterMinConfidence(nextRouterConfig.minConfidence)
      setRouterFallbackToRules(nextRouterConfig.fallbackToRules)
      setOriginalRouterConfig(nextRouterConfig)
    } catch (err) {
      setError(errorMessage(err, "加载 Agent 配置失败"))
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    loadConfig()
  }, [])

  async function savePrompt(key: string) {
    const isAgent = key === AGENT_SYSTEM_PROMPT_KEY
    const value = (isAgent ? agentPrompt : deepAgentsPrompt).trim()
      || (isAgent ? DEFAULT_AGENT_SYSTEM_PROMPT : DEFAULT_DEEP_AGENTS_SYSTEM_PROMPT)
    setSavingKey(key)
    setError(null)
    const toastId = toast.loading("正在保存提示词...")
    try {
      const settings = await updateSettings({ [key]: value })
      const savedValue = settings[key] || value
      if (isAgent) {
        setAgentPrompt(savedValue)
        setOriginalAgentPrompt(savedValue)
      } else {
        setDeepAgentsPrompt(savedValue)
        setOriginalDeepAgentsPrompt(savedValue)
      }
      await loadVersions()
      setLastSavedAt(new Date().toLocaleTimeString())
      toast.success("提示词已保存", { id: toastId })
    } catch (err) {
      const message = errorMessage(err, "保存提示词失败")
      setError(message)
      toast.error("保存失败", { id: toastId, description: message })
    } finally {
      setSavingKey(null)
    }
  }

  async function restorePrompt(key: string) {
    if (!window.confirm("确认恢复默认提示词？当前编辑内容会被覆盖。")) return
    setSavingKey(key)
    setError(null)
    const toastId = toast.loading("正在恢复默认提示词...")
    try {
      const settings = await restoreDefaultSetting(key)
      const agentValue = settings[AGENT_SYSTEM_PROMPT_KEY] || DEFAULT_AGENT_SYSTEM_PROMPT
      const deepValue = settings[DEEP_AGENTS_SYSTEM_PROMPT_KEY] || DEFAULT_DEEP_AGENTS_SYSTEM_PROMPT
      setAgentPrompt(agentValue)
      setDeepAgentsPrompt(deepValue)
      setOriginalAgentPrompt(agentValue)
      setOriginalDeepAgentsPrompt(deepValue)
      await loadVersions()
      setLastSavedAt(new Date().toLocaleTimeString())
      toast.success("已恢复默认提示词", { id: toastId })
    } catch (err) {
      const message = errorMessage(err, "恢复默认提示词失败")
      setError(message)
      toast.error("恢复失败", { id: toastId, description: message })
    } finally {
      setSavingKey(null)
    }
  }

  async function saveMemoryConfig() {
    setSavingKey("memory")
    setError(null)
    const toastId = toast.loading("正在保存长期记忆配置...")
    try {
      const normalizedLimit = String(Math.max(1, Math.min(20, Number(memoryRetrievalLimit) || 6)))
      const normalizedTypes = memoryEnabledTypes
        .split(",")
        .map((item) => item.trim())
        .filter(Boolean)
        .join(",") || "user_profile,project_knowledge,custom"
      const savedConfig = {
        autoSaveEnabled: memoryAutoSaveEnabled,
        retrievalLimit: normalizedLimit,
        enabledTypes: normalizedTypes,
        writePrompt: memoryWritePrompt.trim() || DEFAULT_MEMORY_WRITE_PROMPT,
        retrievalPrompt: memoryRetrievalPrompt.trim() || DEFAULT_MEMORY_RETRIEVAL_PROMPT,
      }
      const settings = await updateSettings({
        [AGENT_MEMORY_AUTO_SAVE_KEY]: String(savedConfig.autoSaveEnabled),
        [AGENT_MEMORY_RETRIEVAL_LIMIT_KEY]: savedConfig.retrievalLimit,
        [AGENT_MEMORY_ENABLED_TYPES_KEY]: savedConfig.enabledTypes,
        [AGENT_MEMORY_WRITE_PROMPT_KEY]: savedConfig.writePrompt,
        [AGENT_MEMORY_RETRIEVAL_PROMPT_KEY]: savedConfig.retrievalPrompt,
      })
      const nextConfig = {
        autoSaveEnabled: (settings[AGENT_MEMORY_AUTO_SAVE_KEY] ?? String(savedConfig.autoSaveEnabled)) !== "false",
        retrievalLimit: settings[AGENT_MEMORY_RETRIEVAL_LIMIT_KEY] || savedConfig.retrievalLimit,
        enabledTypes: settings[AGENT_MEMORY_ENABLED_TYPES_KEY] || savedConfig.enabledTypes,
        writePrompt: settings[AGENT_MEMORY_WRITE_PROMPT_KEY] || savedConfig.writePrompt,
        retrievalPrompt: settings[AGENT_MEMORY_RETRIEVAL_PROMPT_KEY] || savedConfig.retrievalPrompt,
      }
      setMemoryAutoSaveEnabled(nextConfig.autoSaveEnabled)
      setMemoryRetrievalLimit(nextConfig.retrievalLimit)
      setMemoryEnabledTypes(nextConfig.enabledTypes)
      setMemoryWritePrompt(nextConfig.writePrompt)
      setMemoryRetrievalPrompt(nextConfig.retrievalPrompt)
      setOriginalMemoryConfig(nextConfig)
      setLastSavedAt(new Date().toLocaleTimeString())
      toast.success("长期记忆配置已保存", { id: toastId })
    } catch (err) {
      const message = errorMessage(err, "保存长期记忆配置失败")
      setError(message)
      toast.error("保存失败", { id: toastId, description: message })
    } finally {
      setSavingKey(null)
    }
  }

  async function saveRouterConfig() {
    setSavingKey("router")
    setError(null)
    const toastId = toast.loading("正在保存 Agent 路由配置...")
    try {
      const confidence = String(Math.max(0, Math.min(1, Number(routerMinConfidence) || 0.7)))
      const savedConfig = {
        enabled: routerEnabled,
        prompt: routerPrompt.trim() || DEFAULT_ROUTER_PROMPT,
        minConfidence: confidence,
        fallbackToRules: routerFallbackToRules,
      }
      const settings = await updateSettings({
        [AGENT_ROUTER_ENABLED_KEY]: String(savedConfig.enabled),
        [AGENT_ROUTER_PROMPT_KEY]: savedConfig.prompt,
        [AGENT_ROUTER_MIN_CONFIDENCE_KEY]: savedConfig.minConfidence,
        [AGENT_ROUTER_FALLBACK_TO_RULES_KEY]: String(savedConfig.fallbackToRules),
      })
      const nextConfig = {
        enabled: (settings[AGENT_ROUTER_ENABLED_KEY] ?? String(savedConfig.enabled)) !== "false",
        prompt: settings[AGENT_ROUTER_PROMPT_KEY] || savedConfig.prompt,
        minConfidence: settings[AGENT_ROUTER_MIN_CONFIDENCE_KEY] || savedConfig.minConfidence,
        fallbackToRules: (settings[AGENT_ROUTER_FALLBACK_TO_RULES_KEY] ?? String(savedConfig.fallbackToRules)) !== "false",
      }
      setRouterEnabled(nextConfig.enabled)
      setRouterPrompt(nextConfig.prompt)
      setRouterMinConfidence(nextConfig.minConfidence)
      setRouterFallbackToRules(nextConfig.fallbackToRules)
      setOriginalRouterConfig(nextConfig)
      setLastSavedAt(new Date().toLocaleTimeString())
      toast.success("Agent 路由配置已保存", { id: toastId })
    } catch (err) {
      const message = errorMessage(err, "保存 Agent 路由配置失败")
      setError(message)
      toast.error("保存失败", { id: toastId, description: message })
    } finally {
      setSavingKey(null)
    }
  }

  async function saveAllPrompts() {
    setSavingKey("all")
    try {
      await savePrompt(AGENT_SYSTEM_PROMPT_KEY)
      await savePrompt(DEEP_AGENTS_SYSTEM_PROMPT_KEY)
      if (dirtyMemoryConfig) await saveMemoryConfig()
      if (dirtyRouterConfig) await saveRouterConfig()
    } finally {
      setSavingKey(null)
    }
  }

  async function toggleToolAccess(tool: AgentToolAccess, agentEnabled: boolean) {
    setUpdatingToolCode(tool.toolCode)
    setError(null)
    const previousTools = tools
    setTools((current) => current.map((item) => (item.toolCode === tool.toolCode ? { ...item, agentEnabled } : item)))
    try {
      const updated = await updateAdminAgentToolAccess(tool.toolCode, agentEnabled)
      setTools((current) => current.map((item) => (item.toolCode === tool.toolCode ? updated : item)))
      toast.success(agentEnabled ? "已启用 Agent 工具" : "已禁用 Agent 工具", { description: tool.toolName })
    } catch (err) {
      const message = errorMessage(err, "更新 Agent 工具可见性失败")
      setTools(previousTools)
      setError(message)
      toast.error("更新失败", { description: message })
    } finally {
      setUpdatingToolCode(null)
    }
  }

  async function bulkUpdateFilteredTools(agentEnabled: boolean) {
    const targets = filteredTools.filter((tool) => tool.agentEnabled !== agentEnabled)
    if (!targets.length) {
      toast.info(agentEnabled ? "当前筛选结果已全部启用" : "当前筛选结果已全部禁用")
      return
    }
    if (!window.confirm(`${agentEnabled ? "启用" : "禁用"}当前筛选出的 ${targets.length} 个工具？`)) return
    setBulkUpdatingTools(true)
    setError(null)
    const toastId = toast.loading(agentEnabled ? "正在批量启用工具..." : "正在批量禁用工具...")
    try {
      const result = await bulkUpdateAdminAgentToolAccess(targets.map((tool) => tool.toolCode), agentEnabled)
      if (result.failedToolCodes.length) {
        toast.warning("部分工具更新失败", { id: toastId, description: `${result.failedToolCodes.length} 个失败` })
      } else {
        toast.success(agentEnabled ? "批量启用完成" : "批量禁用完成", { id: toastId, description: `${targets.length} 个工具已更新` })
      }
      await loadTools()
    } catch (err) {
      const message = errorMessage(err, "批量更新 Agent 工具可见性失败")
      setError(message)
      toast.error("批量更新失败", { id: toastId, description: message })
    } finally {
      setBulkUpdatingTools(false)
    }
  }

  async function testBoundModel(tool: AgentToolAccess) {
    if (!tool.modelConfigId) {
      toast.info("该工具没有绑定模型")
      return
    }
    setTestingModelConfigId(tool.modelConfigId)
    const toastId = toast.loading("正在测试绑定模型...", { description: tool.modelConfigName || tool.modelName || tool.toolName })
    try {
      const configs = await fetchAgentModelConfigs()
      const config = configs.find((item) => item.id === tool.modelConfigId)
      if (!config) {
        toast.error("未找到绑定模型配置", { id: toastId, description: tool.modelConfigName || tool.modelName || String(tool.modelConfigId) })
        return
      }
      const result = await testAgentModelConfig({
        displayName: config.displayName || undefined,
        configCode: config.configCode || undefined,
        provider: config.provider,
        modelName: config.modelName,
        baseUrl: config.baseUrl || undefined,
        minimaxGroupId: config.minimaxGroupId || undefined,
        timeoutSeconds: config.timeoutSeconds,
        connectTimeoutSeconds: config.connectTimeoutSeconds ?? undefined,
        readTimeoutSeconds: config.readTimeoutSeconds ?? undefined,
        inputTokenPricePer1m: config.inputTokenPricePer1m ?? ((config.inputTokenPricePer1k || 0) * 1000),
        outputTokenPricePer1m: config.outputTokenPricePer1m ?? ((config.outputTokenPricePer1k || 0) * 1000),
        billingUnit: config.billingUnit || "TOKEN_PER_M",
        unitPrice: config.unitPrice || 0,
        enabled: config.enabled,
        agentEnabled: config.agentEnabled ?? true,
        isDefault: config.isDefault ?? false,
        capabilities: config.capabilities ?? undefined,
      })
      if (result.success) {
        toast.success("绑定模型连通性正常", { id: toastId, description: `${result.provider} / ${result.modelName}，${result.latencyMs}ms` })
      } else {
        toast.error("绑定模型测试失败", { id: toastId, description: result.message })
      }
    } catch (err) {
      const message = errorMessage(err, "绑定模型测试失败")
      toast.error("绑定模型测试失败", { id: toastId, description: message })
    } finally {
      setTestingModelConfigId(null)
    }
  }

  async function runRouteDebug() {
    const message = routeDebugMessage.trim()
    if (!message) {
      toast.info("先输入一句用户请求")
      return
    }
    setRouteDebugging(true)
    setError(null)
    try {
      setRouteDebugResult(await debugAdminAgentRoute(message))
    } catch (err) {
      const message = errorMessage(err, "路由调试失败")
      setError(message)
      toast.error("路由调试失败", { description: message })
    } finally {
      setRouteDebugging(false)
    }
  }

  const modelBoundToolCount = useMemo(() => tools.filter((tool) => tool.modelConfigId != null || tool.modelName).length, [tools])
  const agentEnabledToolCount = useMemo(() => tools.filter((tool) => tool.agentEnabled).length, [tools])
  const failedToolCount = useMemo(() => tools.filter((tool) => (tool.healthStatus || "UNKNOWN").toUpperCase() === "FAILED").length, [tools])
  const unknownToolCount = useMemo(() => tools.filter((tool) => (tool.healthStatus || "UNKNOWN").toUpperCase() === "UNKNOWN").length, [tools])
  const unboundToolCount = useMemo(() => tools.filter((tool) => tool.modelConfigId == null && !tool.modelName).length, [tools])
  const modalityOptions = useMemo(() => Array.from(new Set(tools.map((tool) => modalityKey(tool.outputModality)))).sort(), [tools])
  const filteredTools = useMemo(() => {
    const query = toolQuery.trim().toLowerCase()
    return tools.filter((tool) => {
      const matchesQuery = !query || [
        tool.toolName,
        tool.toolCode,
        tool.description,
        tool.modelConfigName,
        tool.modelName,
        tool.executionHandler,
      ].some((value) => String(value || "").toLowerCase().includes(query))
      const matchesModality = toolModalityFilter === "all" || modalityKey(tool.outputModality) === toolModalityFilter
      return matchesQuery && matchesModality && toolMatchesStatus(tool, toolStatusFilter)
    })
  }, [tools, toolQuery, toolModalityFilter, toolStatusFilter])
  const groupedTools = useMemo(() => {
    const groups = new Map<string, AgentToolAccess[]>()
    for (const tool of filteredTools) {
      const key = modalityKey(tool.outputModality)
      groups.set(key, [...(groups.get(key) || []), tool])
    }
    return Array.from(groups.entries())
      .map(([key, items]) => ({
        key,
        label: modalityLabel(key),
        tools: items.sort((left, right) => left.toolName.localeCompare(right.toolName, "zh-CN")),
      }))
      .sort((left, right) => left.label.localeCompare(right.label, "zh-CN"))
  }, [filteredTools])

  return (
    <AdminLayout>
      <AdminHeader title="Agent 运行控制台" description="管理 Agent 提示词、可见工具、路由判断和工具链路健康。" />

      <main className="space-y-6 p-6">
        {error && (
          <Alert variant="destructive">
            <AlertTriangle />
            <AlertTitle>配置异常</AlertTitle>
            <AlertDescription>{error}</AlertDescription>
          </Alert>
        )}

        <section className="grid gap-4 md:grid-cols-4">
          <MetricCard icon={<Bot className="h-5 w-5" />} title="Agent 模型" value="后台默认" description="由 Agent 模型配置决定" />
          <MetricCard icon={<Wrench className="h-5 w-5" />} title="Agent 可见工具" value={loading ? "-" : String(agentEnabledToolCount)} description={`${tools.length} 个在线工具`} />
          <MetricCard icon={<AlertTriangle className="h-5 w-5" />} title="异常工具" value={loading ? "-" : String(failedToolCount)} description={`${unknownToolCount} 个健康未知`} />
          <MetricCard icon={<Route className="h-5 w-5" />} title="最近路由来源" value={routeDebugResult?.decisionSource || "-"} description={lastSavedAt ? `已保存 ${lastSavedAt}` : dirtyPrompts ? `有 ${dirtyPrompts} 项未保存` : "配置已同步"} />
        </section>

        <Tabs defaultValue="overview" className="space-y-6">
          <TabsList className="flex h-auto flex-wrap justify-start gap-2 bg-transparent p-0">
            <TabsTrigger value="overview">总览</TabsTrigger>
            <TabsTrigger value="prompts">提示词</TabsTrigger>
            <TabsTrigger value="router">路由策略</TabsTrigger>
            <TabsTrigger value="memory">长期记忆</TabsTrigger>
            <TabsTrigger value="tools">工具读取范围</TabsTrigger>
            <TabsTrigger value="route">路由调试器</TabsTrigger>
          </TabsList>

          <TabsContent value="overview" className="space-y-6">
            <OverviewSection
              loading={loading}
              tools={tools}
              modelBoundToolCount={modelBoundToolCount}
              unboundToolCount={unboundToolCount}
              failedToolCount={failedToolCount}
              dirtyPrompts={dirtyPrompts}
            />
          </TabsContent>

          <TabsContent value="prompts" className="space-y-6">
            <PromptCard
              title="普通 Agent 系统提示词"
              description="用于常规对话、工具推荐和参数追问。运行时会自动追加启用工具清单。"
              settingKey={AGENT_SYSTEM_PROMPT_KEY}
              value={agentPrompt}
              originalValue={originalAgentPrompt}
              versions={agentVersions}
              loading={loading}
              saving={savingKey === AGENT_SYSTEM_PROMPT_KEY || savingKey === "all"}
              minHeight="min-h-72"
              icon={<Bot className="h-5 w-5" />}
              onChange={setAgentPrompt}
              onSave={() => savePrompt(AGENT_SYSTEM_PROMPT_KEY)}
              onRestore={() => restorePrompt(AGENT_SYSTEM_PROMPT_KEY)}
            />
            <PromptCard
              title="Deep Agents 系统提示词"
              description="用于复杂工作区 Agent、文件上下文和记忆能力。"
              settingKey={DEEP_AGENTS_SYSTEM_PROMPT_KEY}
              value={deepAgentsPrompt}
              originalValue={originalDeepAgentsPrompt}
              versions={deepAgentVersions}
              loading={loading}
              saving={savingKey === DEEP_AGENTS_SYSTEM_PROMPT_KEY || savingKey === "all"}
              minHeight="min-h-44"
              icon={<BrainCircuit className="h-5 w-5" />}
              onChange={setDeepAgentsPrompt}
              onSave={() => savePrompt(DEEP_AGENTS_SYSTEM_PROMPT_KEY)}
              onRestore={() => restorePrompt(DEEP_AGENTS_SYSTEM_PROMPT_KEY)}
            />
          </TabsContent>

          <TabsContent value="router" className="space-y-6">
            <RouterConfigCard
              loading={loading}
              saving={savingKey === "router" || savingKey === "all"}
              enabled={routerEnabled}
              prompt={routerPrompt}
              minConfidence={routerMinConfidence}
              fallbackToRules={routerFallbackToRules}
              dirty={Boolean(dirtyRouterConfig)}
              onEnabledChange={setRouterEnabled}
              onPromptChange={setRouterPrompt}
              onMinConfidenceChange={setRouterMinConfidence}
              onFallbackToRulesChange={setRouterFallbackToRules}
              onSave={saveRouterConfig}
            />
          </TabsContent>

          <TabsContent value="memory" className="space-y-6">
            <MemoryConfigCard
              loading={loading}
              saving={savingKey === "memory" || savingKey === "all"}
              autoSaveEnabled={memoryAutoSaveEnabled}
              retrievalLimit={memoryRetrievalLimit}
              enabledTypes={memoryEnabledTypes}
              writePrompt={memoryWritePrompt}
              retrievalPrompt={memoryRetrievalPrompt}
              dirty={Boolean(dirtyMemoryConfig)}
              onAutoSaveChange={setMemoryAutoSaveEnabled}
              onRetrievalLimitChange={setMemoryRetrievalLimit}
              onEnabledTypesChange={setMemoryEnabledTypes}
              onWritePromptChange={setMemoryWritePrompt}
              onRetrievalPromptChange={setMemoryRetrievalPrompt}
              onSave={saveMemoryConfig}
            />
          </TabsContent>

          <TabsContent value="tools" className="space-y-6">
            <ToolConsole
              loading={loading}
              tools={tools}
              filteredTools={filteredTools}
              groupedTools={groupedTools}
              toolQuery={toolQuery}
              toolModalityFilter={toolModalityFilter}
              toolStatusFilter={toolStatusFilter}
              modalityOptions={modalityOptions}
              updatingToolCode={updatingToolCode}
              bulkUpdatingTools={bulkUpdatingTools}
              testingModelConfigId={testingModelConfigId}
              modelBoundToolCount={modelBoundToolCount}
              unboundToolCount={unboundToolCount}
              failedToolCount={failedToolCount}
              onQueryChange={setToolQuery}
              onModalityFilterChange={setToolModalityFilter}
              onStatusFilterChange={setToolStatusFilter}
              onToggleTool={toggleToolAccess}
              onBulkUpdate={bulkUpdateFilteredTools}
              onTestBoundModel={testBoundModel}
            />
          </TabsContent>

          <TabsContent value="route" className="space-y-6">
            <RouteDebugger
              message={routeDebugMessage}
              result={routeDebugResult}
              loading={loading}
              debugging={routeDebugging}
              onMessageChange={setRouteDebugMessage}
              onRun={runRouteDebug}
            />
          </TabsContent>
        </Tabs>

        <div className="sticky bottom-4 z-20 flex justify-end gap-3">
          <Button variant="outline" onClick={loadConfig} disabled={loading || Boolean(savingKey)}>
            <RefreshCw className="mr-2 h-4 w-4" />
            刷新
          </Button>
          <Button onClick={saveAllPrompts} disabled={loading || Boolean(savingKey) || dirtyPrompts === 0}>
            <Save className="mr-2 h-4 w-4" />
            {dirtyPrompts ? `保存 ${dirtyPrompts} 项未保存` : lastSavedAt ? `已保存 ${lastSavedAt}` : "保存配置"}
          </Button>
        </div>
      </main>
    </AdminLayout>
  )
}

function MetricCard({ icon, title, value, description }: { icon: ReactNode; title: string; value: string; description: string }) {
  return (
    <Card className="rounded-lg">
      <CardHeader className="flex-row items-center gap-3 space-y-0">
        <div className="rounded-md border bg-muted p-2 text-primary">{icon}</div>
        <div>
          <CardDescription>{title}</CardDescription>
          <CardTitle className="text-2xl">{value}</CardTitle>
          <CardDescription>{description}</CardDescription>
        </div>
      </CardHeader>
    </Card>
  )
}

function OverviewSection({
  loading,
  tools,
  modelBoundToolCount,
  unboundToolCount,
  failedToolCount,
  dirtyPrompts,
}: {
  loading: boolean
  tools: AgentToolAccess[]
  modelBoundToolCount: number
  unboundToolCount: number
  failedToolCount: number
  dirtyPrompts: number
}) {
  return (
    <div className="grid gap-6 xl:grid-cols-3">
      <Card className="rounded-lg">
        <CardHeader>
          <CardTitle className="flex items-center gap-2"><Database className="h-5 w-5" />配置链路</CardTitle>
          <CardDescription>Agent 模型负责路由和对话；工具执行继续使用各自绑定模型。</CardDescription>
        </CardHeader>
        <CardContent className="space-y-3 text-sm text-muted-foreground">
          <div>提示词保存到 system_settings，并记录版本。</div>
          <div>工具开关保存到 Agent 扩展表，禁用后不会进入运行时工具清单。</div>
          <div>路由调试会真实调用 Agent LLM，可能产生模型调用成本。</div>
        </CardContent>
      </Card>
      <Card className="rounded-lg">
        <CardHeader>
          <CardTitle className="flex items-center gap-2"><Wrench className="h-5 w-5" />工具状态</CardTitle>
          <CardDescription>快速确认可见工具、模型绑定和健康风险。</CardDescription>
        </CardHeader>
        <CardContent className="grid grid-cols-2 gap-3">
          <SmallStat label="在线工具" value={loading ? "-" : tools.length} />
          <SmallStat label="已绑定模型" value={loading ? "-" : modelBoundToolCount} />
          <SmallStat label="未绑定模型" value={loading ? "-" : unboundToolCount} />
          <SmallStat label="健康失败" value={loading ? "-" : failedToolCount} />
        </CardContent>
      </Card>
      <Card className="rounded-lg">
        <CardHeader>
          <CardTitle className="flex items-center gap-2"><CheckCircle2 className="h-5 w-5" />配置安全</CardTitle>
          <CardDescription>保存前先看 diff，必要时恢复默认。</CardDescription>
        </CardHeader>
        <CardContent className="space-y-3 text-sm text-muted-foreground">
          <div>当前提示词未保存项：{dirtyPrompts}</div>
          <div>空提示词保存时会自动回退到默认提示词。</div>
          <div>最近版本可用于人工回溯。</div>
        </CardContent>
      </Card>
    </div>
  )
}

function SmallStat({ label, value }: { label: string; value: string | number }) {
  return (
    <div className="rounded-lg border bg-muted/40 p-3">
      <div className="text-2xl font-semibold">{value}</div>
      <div className="text-xs text-muted-foreground">{label}</div>
    </div>
  )
}

function PromptCard({
  title,
  description,
  settingKey,
  value,
  originalValue,
  versions,
  loading,
  saving,
  minHeight,
  icon,
  onChange,
  onSave,
  onRestore,
}: {
  title: string
  description: string
  settingKey: string
  value: string
  originalValue: string
  versions: SettingVersion[]
  loading: boolean
  saving: boolean
  minHeight: string
  icon: ReactNode
  onChange: (value: string) => void
  onSave: () => void
  onRestore: () => void
}) {
  const dirty = value !== originalValue
  return (
    <Card className="rounded-lg">
      <CardHeader>
        <div className="flex flex-wrap items-start justify-between gap-4">
          <div>
            <CardTitle className="flex items-center gap-2">{icon}{title}</CardTitle>
            <CardDescription>{description}</CardDescription>
          </div>
          <div className="flex flex-wrap items-center gap-2">
            <Badge variant="secondary">{settingKey}</Badge>
            {dirty ? <Badge variant="destructive">未保存</Badge> : <Badge variant="outline">已同步</Badge>}
          </div>
        </div>
      </CardHeader>
      <CardContent className="space-y-4">
        <div className="space-y-2">
          <Label>System Prompt</Label>
          {loading ? (
            <Skeleton className={`${minHeight} w-full`} />
          ) : (
            <Textarea value={value} onChange={(event) => onChange(event.target.value)} className={`${minHeight} resize-y font-mono text-sm leading-6`} />
          )}
          <div className="text-xs text-muted-foreground">留空保存会自动使用默认提示词。</div>
        </div>
        <div className="grid gap-4 lg:grid-cols-2">
          <div className="space-y-2">
            <Label>改动 Diff</Label>
            <pre className="max-h-48 overflow-auto rounded-lg border bg-muted/30 p-3 text-xs text-muted-foreground">{promptDiff(originalValue, value)}</pre>
          </div>
          <div className="space-y-2">
            <Label>最近版本</Label>
            <div className="max-h-48 space-y-2 overflow-auto rounded-lg border bg-muted/30 p-3">
              {versions.length ? versions.map((version) => (
                <div key={version.id} className="text-xs">
                  <div className="flex items-center justify-between gap-2">
                    <span className="font-medium">#{version.id}</span>
                    <span className="text-muted-foreground">{formatTime(version.createdAt)}</span>
                  </div>
                  <div className="line-clamp-2 text-muted-foreground">{version.settingValue}</div>
                </div>
              )) : <div className="text-xs text-muted-foreground">暂无版本记录</div>}
            </div>
          </div>
        </div>
        <div className="flex justify-end gap-2">
          <Button variant="outline" onClick={onRestore} disabled={saving}>恢复默认</Button>
          <Button onClick={onSave} disabled={saving || !dirty}>{saving ? "保存中..." : "保存此提示词"}</Button>
        </div>
      </CardContent>
    </Card>
  )
}

function RouterConfigCard({
  loading,
  saving,
  enabled,
  prompt,
  minConfidence,
  fallbackToRules,
  dirty,
  onEnabledChange,
  onPromptChange,
  onMinConfidenceChange,
  onFallbackToRulesChange,
  onSave,
}: {
  loading: boolean
  saving: boolean
  enabled: boolean
  prompt: string
  minConfidence: string
  fallbackToRules: boolean
  dirty: boolean
  onEnabledChange: (value: boolean) => void
  onPromptChange: (value: string) => void
  onMinConfidenceChange: (value: string) => void
  onFallbackToRulesChange: (value: boolean) => void
  onSave: () => void
}) {
  return (
    <Card className="rounded-lg">
      <CardHeader>
        <div className="flex flex-wrap items-start justify-between gap-4">
          <div>
            <CardTitle className="flex items-center gap-2"><Route className="h-5 w-5" />Agent Router</CardTitle>
            <CardDescription>控制 Agent 是否使用 LLM Router、最低置信度，以及失败时是否回退规则路由。</CardDescription>
          </div>
          <div className="flex flex-wrap items-center gap-2">
            <Badge variant="secondary">agent.router.*</Badge>
            {dirty ? <Badge variant="destructive">未保存</Badge> : <Badge variant="outline">已同步</Badge>}
          </div>
        </div>
      </CardHeader>
      <CardContent className="space-y-5">
        <div className="grid gap-4 lg:grid-cols-3">
          <div className="rounded-lg border p-4">
            <div className="flex items-center justify-between gap-3">
              <div>
                <Label>启用 LLM Router</Label>
                <p className="mt-1 text-xs text-muted-foreground">关闭后只使用规则路由。</p>
              </div>
              <Switch checked={enabled} disabled={loading || saving} onCheckedChange={onEnabledChange} />
            </div>
          </div>
          <div className="rounded-lg border p-4">
            <div className="flex items-center justify-between gap-3">
              <div>
                <Label>规则兜底</Label>
                <p className="mt-1 text-xs text-muted-foreground">模型低置信度、返回非法工具或异常时回退。</p>
              </div>
              <Switch checked={fallbackToRules} disabled={loading || saving} onCheckedChange={onFallbackToRulesChange} />
            </div>
          </div>
          <div className="space-y-2">
            <Label>最低置信度</Label>
            <Input
              type="number"
              min={0}
              max={1}
              step={0.05}
              value={minConfidence}
              disabled={loading || saving}
              onChange={(event) => onMinConfidenceChange(event.target.value)}
            />
            <p className="text-xs text-muted-foreground">低于该值会写入 router.fallback。</p>
          </div>
        </div>
        <div className="space-y-2">
          <Label>路由提示词</Label>
          <Textarea
            value={prompt}
            disabled={loading || saving}
            onChange={(event) => onPromptChange(event.target.value)}
            className="min-h-72 resize-y font-mono text-sm leading-6"
          />
        </div>
        <div className="flex justify-end gap-2">
          <Button onClick={onSave} disabled={saving || !dirty}>{saving ? "保存中..." : "保存路由配置"}</Button>
        </div>
      </CardContent>
    </Card>
  )
}

function MemoryConfigCard({
  loading,
  saving,
  autoSaveEnabled,
  retrievalLimit,
  enabledTypes,
  writePrompt,
  retrievalPrompt,
  dirty,
  onAutoSaveChange,
  onRetrievalLimitChange,
  onEnabledTypesChange,
  onWritePromptChange,
  onRetrievalPromptChange,
  onSave,
}: {
  loading: boolean
  saving: boolean
  autoSaveEnabled: boolean
  retrievalLimit: string
  enabledTypes: string
  writePrompt: string
  retrievalPrompt: string
  dirty: boolean
  onAutoSaveChange: (value: boolean) => void
  onRetrievalLimitChange: (value: string) => void
  onEnabledTypesChange: (value: string) => void
  onWritePromptChange: (value: string) => void
  onRetrievalPromptChange: (value: string) => void
  onSave: () => void
}) {
  return (
    <Card className="rounded-lg">
      <CardHeader>
        <div className="flex flex-wrap items-start justify-between gap-4">
          <div>
            <CardTitle className="flex items-center gap-2"><Database className="h-5 w-5" />长期记忆配置</CardTitle>
            <CardDescription>控制 Agent 什么时候写入长期记忆、每轮读取多少条，以及写入/读取时使用的约束提示。</CardDescription>
          </div>
          <div className="flex flex-wrap items-center gap-2">
            <Badge variant="secondary">agent.memory.*</Badge>
            {dirty ? <Badge variant="destructive">未保存</Badge> : <Badge variant="outline">已同步</Badge>}
          </div>
        </div>
      </CardHeader>
      <CardContent className="space-y-5">
        <div className="grid gap-4 lg:grid-cols-3">
          <div className="rounded-lg border p-4">
            <div className="flex items-center justify-between gap-3">
              <div>
                <Label>自动记忆</Label>
                <p className="mt-1 text-xs text-muted-foreground">关闭后 Agent 只读取已有记忆，不再自动新增。</p>
              </div>
              <Switch checked={autoSaveEnabled} disabled={loading || saving} onCheckedChange={onAutoSaveChange} />
            </div>
          </div>
          <div className="space-y-2">
            <Label>每轮注入记忆数</Label>
            <Input
              type="number"
              min={1}
              max={20}
              value={retrievalLimit}
              disabled={loading || saving}
              onChange={(event) => onRetrievalLimitChange(event.target.value)}
            />
            <p className="text-xs text-muted-foreground">建议 4-8 条，避免上下文过重。</p>
          </div>
          <div className="space-y-2">
            <Label>允许类型</Label>
            <Input
              value={enabledTypes}
              disabled={loading || saving}
              onChange={(event) => onEnabledTypesChange(event.target.value)}
            />
            <p className="text-xs text-muted-foreground">逗号分隔，例如 user_profile,project_knowledge,custom。</p>
          </div>
        </div>

        <div className="grid gap-4 lg:grid-cols-2">
          <div className="space-y-2">
            <Label>记忆写入提示词</Label>
            <Textarea
              value={writePrompt}
              disabled={loading || saving}
              onChange={(event) => onWritePromptChange(event.target.value)}
              className="min-h-56 resize-y text-sm leading-6"
            />
          </div>
          <div className="space-y-2">
            <Label>记忆读取提示词</Label>
            <Textarea
              value={retrievalPrompt}
              disabled={loading || saving}
              onChange={(event) => onRetrievalPromptChange(event.target.value)}
              className="min-h-56 resize-y text-sm leading-6"
            />
          </div>
        </div>

        <Alert>
          <AlertTriangle className="h-4 w-4" />
          <AlertTitle>边界</AlertTitle>
          <AlertDescription>
            长期记忆只应保存稳定偏好和项目事实；工具结果、媒体 URL、base64、大 JSON 和一次性参数不要进入记忆。
          </AlertDescription>
        </Alert>

        <div className="flex justify-end">
          <Button onClick={onSave} disabled={loading || saving || !dirty}>
            {saving ? "保存中..." : "保存长期记忆配置"}
          </Button>
        </div>
      </CardContent>
    </Card>
  )
}

function ToolConsole(props: {
  loading: boolean
  tools: AgentToolAccess[]
  filteredTools: AgentToolAccess[]
  groupedTools: Array<{ key: string; label: string; tools: AgentToolAccess[] }>
  toolQuery: string
  toolModalityFilter: string
  toolStatusFilter: string
  modalityOptions: string[]
  updatingToolCode: string | null
  bulkUpdatingTools: boolean
  testingModelConfigId: number | null
  modelBoundToolCount: number
  unboundToolCount: number
  failedToolCount: number
  onQueryChange: (value: string) => void
  onModalityFilterChange: (value: string) => void
  onStatusFilterChange: (value: string) => void
  onToggleTool: (tool: AgentToolAccess, agentEnabled: boolean) => void
  onBulkUpdate: (agentEnabled: boolean) => void
  onTestBoundModel: (tool: AgentToolAccess) => void
}) {
  return (
    <Card className="rounded-lg">
      <CardHeader>
        <CardTitle className="flex items-center gap-2"><SlidersHorizontal className="h-5 w-5" />工具读取范围</CardTitle>
        <CardDescription>按输出模态管理 Agent 可见工具；禁用后不会进入 Agent 运行时工具清单。</CardDescription>
      </CardHeader>
      <CardContent className="space-y-4">
        <div className="grid grid-cols-2 gap-3 md:grid-cols-4">
          <SmallStat label="在线工具" value={props.loading ? "-" : props.tools.length} />
          <SmallStat label="当前显示" value={props.loading ? "-" : props.filteredTools.length} />
          <SmallStat label="未绑定模型" value={props.loading ? "-" : props.unboundToolCount} />
          <SmallStat label="健康失败" value={props.loading ? "-" : props.failedToolCount} />
        </div>
        <div className="grid gap-2 lg:grid-cols-[minmax(0,1fr)_180px_180px_auto_auto]">
          <div className="relative">
            <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
            <Input value={props.toolQuery} onChange={(event) => props.onQueryChange(event.target.value)} placeholder="搜索工具名、toolCode、模型或执行器" className="pl-9" />
          </div>
          <Select value={props.toolModalityFilter} onValueChange={props.onModalityFilterChange}>
            <SelectTrigger><SelectValue placeholder="输出模态" /></SelectTrigger>
            <SelectContent>
              <SelectItem value="all">全部模态</SelectItem>
              {props.modalityOptions.map((key) => <SelectItem key={key} value={key}>{modalityLabel(key)}</SelectItem>)}
            </SelectContent>
          </Select>
          <Select value={props.toolStatusFilter} onValueChange={props.onStatusFilterChange}>
            <SelectTrigger><SelectValue placeholder="状态筛选" /></SelectTrigger>
            <SelectContent>
              {TOOL_STATUS_FILTERS.map((item) => <SelectItem key={item.value} value={item.value}>{item.label}</SelectItem>)}
            </SelectContent>
          </Select>
          <Button variant="outline" disabled={props.loading || props.bulkUpdatingTools || !props.filteredTools.length} onClick={() => props.onBulkUpdate(true)}>启用当前筛选</Button>
          <Button variant="outline" disabled={props.loading || props.bulkUpdatingTools || !props.filteredTools.length} onClick={() => props.onBulkUpdate(false)}>禁用当前筛选</Button>
        </div>
        <div className="space-y-4">
          {props.loading ? Array.from({ length: 6 }).map((_, index) => <Skeleton key={index} className="h-24 w-full" />) : null}
          {!props.loading && !props.groupedTools.length ? (
            <div className="rounded-lg border border-dashed p-8 text-center text-sm text-muted-foreground">没有匹配的工具</div>
          ) : null}
          {props.groupedTools.map((group) => (
            <div key={group.key} className="space-y-2">
              <div className="flex items-center justify-between text-sm">
                <span className="font-medium">{group.label}</span>
                <span className="text-muted-foreground">{group.tools.filter((tool) => tool.agentEnabled).length}/{group.tools.length}</span>
              </div>
              <div className="grid gap-3 xl:grid-cols-2">
                {group.tools.map((tool) => (
                  <ToolCard
                    key={tool.id}
                    tool={tool}
                    updating={props.updatingToolCode === tool.toolCode}
                    testingModel={props.testingModelConfigId === tool.modelConfigId}
                    onToggle={props.onToggleTool}
                    onTestBoundModel={props.onTestBoundModel}
                  />
                ))}
              </div>
            </div>
          ))}
        </div>
      </CardContent>
    </Card>
  )
}

function ToolCard({
  tool,
  updating,
  testingModel,
  onToggle,
  onTestBoundModel,
}: {
  tool: AgentToolAccess
  updating: boolean
  testingModel: boolean
  onToggle: (tool: AgentToolAccess, agentEnabled: boolean) => void
  onTestBoundModel: (tool: AgentToolAccess) => void
}) {
  const hasModel = tool.modelConfigId != null || Boolean(tool.modelName)
  const health = healthMeta(tool)
  return (
    <div className="rounded-lg border px-4 py-3">
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0 space-y-2">
          <div>
            <div className="truncate text-sm font-medium">{tool.toolName}</div>
            <div className="truncate text-xs text-muted-foreground">{tool.toolCode}</div>
          </div>
          <div className="flex flex-wrap gap-1.5">
            <Badge variant={hasModel ? "default" : "outline"}>{hasModel ? "有模型" : "未绑定"}</Badge>
            <Badge variant="secondary">{modalityLabel(tool.outputModality)}</Badge>
            <Badge variant={health.variant}>{health.label}</Badge>
          </div>
          <div className="grid gap-1 text-xs text-muted-foreground">
            <div className="truncate">执行器：{tool.executionHandler || tool.toolType || "未配置"}</div>
            <div className="truncate">绑定模型：{tool.modelConfigName || tool.modelName || "未绑定"}</div>
            <div className="truncate">Base URL：{tool.modelBaseUrl || "未配置"}</div>
            <div>超时：连接 {tool.modelConnectTimeoutSeconds ?? "默认"}s / 读取 {tool.modelReadTimeoutSeconds ?? tool.modelTimeoutSeconds ?? "默认"}s / 代理 {tool.modelProxyConfigured ? "已配置" : "未配置"}</div>
            <div>{health.help}</div>
          </div>
          {tool.healthMessage ? <div className="line-clamp-2 text-xs text-destructive">{tool.healthMessage}</div> : null}
          <Button
            type="button"
            size="sm"
            variant="outline"
            disabled={!tool.modelConfigId || testingModel}
            onClick={() => onTestBoundModel(tool)}
          >
            {testingModel ? "测试中..." : "测试绑定模型"}
          </Button>
        </div>
        <div className="flex shrink-0 flex-col items-end gap-2">
          <Switch checked={tool.agentEnabled} disabled={updating} aria-label={`${tool.agentEnabled ? "禁用" : "启用"} ${tool.toolName}`} onCheckedChange={(checked) => onToggle(tool, checked)} />
          <span className="text-xs text-muted-foreground">{tool.agentEnabled ? "启用" : "禁用"}</span>
        </div>
      </div>
    </div>
  )
}

function RouteDebugger({
  message,
  result,
  loading,
  debugging,
  onMessageChange,
  onRun,
}: {
  message: string
  result: AgentRouteDebugResult | null
  loading: boolean
  debugging: boolean
  onMessageChange: (value: string) => void
  onRun: () => void
}) {
  return (
    <Card className="rounded-lg">
      <CardHeader>
        <CardTitle className="flex items-center gap-2"><Route className="h-5 w-5" />路由调试器</CardTitle>
        <CardDescription>输入一句用户请求，使用真实 Agent 路由逻辑查看会选择哪个工具。此操作会调用路由模型，可能产生模型调用成本。</CardDescription>
      </CardHeader>
      <CardContent className="space-y-4">
        <Textarea value={message} onChange={(event) => onMessageChange(event.target.value)} placeholder="例如：生成一张08年一家人除夕夜合影的老照片" className="min-h-28 resize-y text-sm" />
        <Button type="button" variant="outline" disabled={loading || debugging} onClick={onRun}>
          <Route className="mr-2 h-4 w-4" />
          {debugging ? "正在判断..." : "模拟路由"}
        </Button>
        {result ? (
          <div className="space-y-4 rounded-lg border bg-muted/30 p-4">
            <div className="grid gap-3 md:grid-cols-4">
              <SmallStat label="意图" value={result.intent || "-"} />
              <SmallStat label="置信度" value={typeof result.confidence === "number" ? `${Math.round(result.confidence * 100)}%` : "-"} />
              <SmallStat label="输出模态" value={result.requestedOutputModality || "未知"} />
              <SmallStat label="决策来源" value={result.decisionSource || "-"} />
            </div>
            <div className="grid gap-4 lg:grid-cols-2">
              <div className="space-y-2">
                <Label>最终工具</Label>
                <div className="rounded-lg border bg-background p-3 text-sm font-medium">{result.selectedToolCode || "未选择工具"}</div>
              </div>
              <div className="space-y-2">
                <Label>候选工具</Label>
                <div className="min-h-11 rounded-lg border bg-background p-3">
                  {result.candidateToolCodes?.length ? (
                    <div className="flex flex-wrap gap-1">{result.candidateToolCodes.map((code) => <Badge key={code} variant="secondary">{code}</Badge>)}</div>
                  ) : <span className="text-sm text-muted-foreground">无候选工具</span>}
                </div>
              </div>
            </div>
            {result.reason ? (
              <div className="space-y-2">
                <Label>判断原因</Label>
                <div className="rounded-lg border bg-background p-3 text-sm text-muted-foreground">{result.reason}</div>
              </div>
            ) : null}
            <div className="grid gap-4 lg:grid-cols-2">
              <DebugToolList title={`Agent 可见工具 (${result.visibleToolCount ?? result.visibleTools?.length ?? 0})`} tools={(result.visibleTools || []).map((tool) => ({ code: tool.toolCode, name: tool.toolName, meta: tool.autoCallable ? "可自动调用" : "需确认" }))} />
              <DebugToolList title={`被过滤工具 (${result.filteredTools?.length ?? 0})`} tools={(result.filteredTools || []).map((tool) => ({ code: tool.toolCode, name: tool.toolName, meta: tool.reason }))} />
            </div>
          </div>
        ) : null}
      </CardContent>
    </Card>
  )
}

function DebugToolList({ title, tools }: { title: string; tools: Array<{ code: string; name: string; meta: string }> }) {
  return (
    <div className="space-y-2">
      <Label>{title}</Label>
      <div className="max-h-64 space-y-1 overflow-auto rounded-lg border bg-background p-3">
        {tools.length ? tools.map((tool) => (
          <div key={tool.code} className="flex items-center justify-between gap-3 text-xs">
            <span className="min-w-0 truncate">{tool.name || tool.code}</span>
            <span className="shrink-0 text-muted-foreground">{tool.meta}</span>
          </div>
        )) : <div className="text-xs text-muted-foreground">无数据</div>}
      </div>
    </div>
  )
}
