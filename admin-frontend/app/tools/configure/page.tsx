"use client"

import { Suspense, useEffect, useMemo, useState, type ChangeEvent, type DragEvent } from "react"
import Link from "next/link"
import { useRouter, useSearchParams } from "next/navigation"
import {
  ArrowLeft,
  CheckCircle2,
  Image,
  Loader2,
  Save,
  UploadCloud,
  UserRound,
  Video,
  WandSparkles,
  type LucideIcon,
} from "lucide-react"
import { toast } from "sonner"
import { AdminLayout } from "@/components/admin/admin-layout"
import { AdminHeader } from "@/components/admin/header"
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { Textarea } from "@/components/ui/textarea"
import {
  createTool,
  fetchAdminToolDetail,
  generateToolPromptDraft,
  publishTool,
  updateTool,
  updateToolFields,
  uploadToolCover,
} from "@/lib/api/tools"
import { fetchAgentModelConfigs } from "@/lib/api/agent-model"
import type { AgentModelConfig, ToolDetail, ToolFieldPayload, UpsertToolPayload } from "@/lib/api/types"
import { cn } from "@/lib/utils"

type ToolKind = "image" | "video" | "digitalHuman" | "text"
type MediaField = "coverUrl"

interface RuntimeInput {
  fieldKey: string
  fieldName: string
  fieldType: string
  placeholder?: string
  required: boolean
  sortOrder: number
}

interface RuntimeConfig {
  toolKind?: ToolKind | string
  systemPrompt?: string
  adminPrompt?: string
  userInputs?: RuntimeInput[]
}

interface ConfigureForm {
  toolKind: ToolKind
  toolCode: string
  toolName: string
  description: string
  coverUrl: string
  systemPrompt: string
  adminPrompt: string
  estimatedCreditCost: string
  modelConfigId: string
}

const RUNTIME_PATTERN = /<!--\s*ai-tool-runtime:([\s\S]*?)\s*-->/
const UI_PATTERN = /<!--\s*ai-tool-ui:([\s\S]*?)\s*-->/

const kindOptions: Array<{
  kind: ToolKind
  title: string
  desc: string
  icon: LucideIcon
  accent: string
}> = [
  {
    kind: "image",
    title: "图片工具",
    desc: "用户上传图片，后台提示词决定编辑效果",
    icon: Image,
    accent: "border-pink-300 bg-pink-50 text-pink-700",
  },
  {
    kind: "video",
    title: "视频工具",
    desc: "预留上传视频后交给视频模型处理",
    icon: Video,
    accent: "border-sky-300 bg-sky-50 text-sky-700",
  },
  {
    kind: "digitalHuman",
    title: "数字人",
    desc: "预留脚本文字、人物素材和数字人执行器",
    icon: UserRound,
    accent: "border-violet-300 bg-violet-50 text-violet-700",
  },
  {
    kind: "text",
    title: "文本工具",
    desc: "普通文本输入和提示词生成",
    icon: WandSparkles,
    accent: "border-amber-300 bg-amber-50 text-amber-700",
  },
]

const initialForm: ConfigureForm = {
  toolKind: "image",
  toolCode: "",
  toolName: "",
  description: "",
  coverUrl: "",
  systemPrompt: "You are an AI image editing engine. Follow the user asset and the hidden tool instruction exactly.",
  adminPrompt: "基于用户上传的图片 {{sourceImageUrl}} 生成最终效果。请保持主体自然、边缘干净、画面质量高清。",
  estimatedCreditCost: "3",
  modelConfigId: "",
}

function parseRuntimeConfig(configNote?: string | null): RuntimeConfig {
  const match = (configNote || "").match(RUNTIME_PATTERN)
  if (!match) return {}
  try {
    return JSON.parse(match[1]) as RuntimeConfig
  } catch {
    return {}
  }
}

function stripRuntimeAndUi(configNote?: string | null) {
  return (configNote || "").replace(RUNTIME_PATTERN, "").replace(UI_PATTERN, "").trim()
}

function normalizeKind(value?: string | null): ToolKind {
  if (value === "image" || value === "video" || value === "digitalHuman" || value === "text") return value
  return "image"
}

function deriveKindFromTool(tool: ToolDetail): ToolKind {
  const runtime = parseRuntimeConfig(tool.configNote)
  if (runtime.toolKind) return normalizeKind(runtime.toolKind)
  if (tool.executionHandler === "DIGITAL_HUMAN") return "digitalHuman"
  if (tool.outputModality === "VIDEO") return "video"
  if (tool.outputModality === "IMAGE") return "image"
  return "text"
}

function defaultInputsForKind(kind: ToolKind): RuntimeInput[] {
  if (kind === "image") {
    return [
      {
        fieldKey: "sourceImageUrl",
        fieldName: "上传图片",
        fieldType: "image_upload",
        placeholder: "上传需要处理的图片",
        required: true,
        sortOrder: 1,
      },
    ]
  }
  if (kind === "video") {
    return [
      {
        fieldKey: "sourceVideoUrl",
        fieldName: "上传视频",
        fieldType: "video_upload",
        placeholder: "上传需要处理的视频",
        required: true,
        sortOrder: 1,
      },
    ]
  }
  if (kind === "digitalHuman") {
    return [
      {
        fieldKey: "scriptText",
        fieldName: "口播文案",
        fieldType: "textarea",
        placeholder: "输入数字人要讲的内容",
        required: true,
        sortOrder: 1,
      },
      {
        fieldKey: "avatarImageUrl",
        fieldName: "人物形象",
        fieldType: "image_upload",
        placeholder: "上传数字人参考形象，可选",
        required: false,
        sortOrder: 2,
      },
    ]
  }
  return [
    {
      fieldKey: "prompt",
      fieldName: "输入内容",
      fieldType: "textarea",
      placeholder: "输入希望 AI 处理的内容",
      required: true,
      sortOrder: 1,
    },
  ]
}

function toFieldPayload(input: RuntimeInput): ToolFieldPayload {
  return {
    fieldKey: input.fieldKey,
    fieldName: input.fieldName,
    fieldType: input.fieldType,
    placeholder: input.placeholder || "",
    required: input.required,
    executionRequired: input.required,
    userRequired: input.required,
    agentFillStrategy: input.required ? "ask_user" : "default",
    riskLevel: input.required ? "MEDIUM" : "LOW",
    sortOrder: input.sortOrder,
  }
}

function executionForKind(kind: ToolKind) {
  if (kind === "image") {
    return {
      toolType: "IMAGE_TO_IMAGE",
      inputModality: "IMAGE",
      outputModality: "IMAGE",
      executionHandler: "IMAGE_GENERATION",
    }
  }
  if (kind === "video") {
    return {
      toolType: "VIDEO_GENERATION",
      inputModality: "VIDEO",
      outputModality: "VIDEO",
      executionHandler: "VIDEO_GENERATION",
    }
  }
  if (kind === "digitalHuman") {
    return {
      toolType: "VIDEO_GENERATION",
      inputModality: "TEXT",
      outputModality: "VIDEO",
      executionHandler: "DIGITAL_HUMAN",
    }
  }
  return {
    toolType: "TEXT_GENERATION",
    inputModality: "TEXT",
    outputModality: "TEXT",
    executionHandler: "TEXT_GENERATION",
  }
}

function serializeConfigNote(form: ConfigureForm) {
  const inputs = defaultInputsForKind(form.toolKind)
  const runtime = {
    toolKind: form.toolKind,
    systemPrompt: form.systemPrompt.trim(),
    adminPrompt: form.adminPrompt.trim(),
    userInputs: inputs,
  }
  const display = {
    primaryColor: "#ff2f6d",
    welcomeMessage: "",
    mediaDisplayMode: form.toolKind === "image" ? "comparison" : "effect",
    modelIconUrl: "",
    heroTitle: form.toolName.trim(),
    heroSubtitle: form.description.trim(),
    demoThumbnails: [form.coverUrl.trim()].filter(Boolean),
    useCases: [],
    steps: inputs.map((input) => input.fieldName).concat("点击生成"),
    recommendedToolCodes: [],
  }
  return `<!-- ai-tool-runtime:${JSON.stringify(runtime)} -->\n<!-- ai-tool-ui:${JSON.stringify(display)} -->`
}

function displayName(config: AgentModelConfig) {
  return config.displayName || config.configCode || `${config.provider} / ${config.modelName}`
}

function isVideoAsset(url: string) {
  return /\.(mp4|webm|mov|m4v)(\?|#|$)/i.test(url)
}

function ConfigurePageContent() {
  const router = useRouter()
  const searchParams = useSearchParams()
  const toolId = Number(searchParams.get("toolId") || 0) || null
  const [form, setForm] = useState<ConfigureForm>(initialForm)
  const [modelConfigs, setModelConfigs] = useState<AgentModelConfig[]>([])
  const [editingTool, setEditingTool] = useState<ToolDetail | null>(null)
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [promptGenerating, setPromptGenerating] = useState(false)
  const [uploadingField, setUploadingField] = useState<MediaField | null>(null)
  const [draggingField, setDraggingField] = useState<MediaField | null>(null)
  const [error, setError] = useState<string | null>(null)

  const selectedKindMeta = useMemo(
    () => kindOptions.find((item) => item.kind === form.toolKind) || kindOptions[0],
    [form.toolKind],
  )
  const SelectedKindIcon = selectedKindMeta.icon
  const userInputs = useMemo(() => defaultInputsForKind(form.toolKind), [form.toolKind])
  const execution = useMemo(() => executionForKind(form.toolKind), [form.toolKind])
  const enabledModels = useMemo(() => modelConfigs.filter((config) => config.enabled), [modelConfigs])

  function updateForm<K extends keyof ConfigureForm>(key: K, value: ConfigureForm[K]) {
    setForm((current) => ({ ...current, [key]: value }))
  }

  async function uploadMedia(field: MediaField, file?: File | null) {
    if (!file) return
    const supported =
      file.type.startsWith("image/") ||
      file.type.startsWith("video/") ||
      /\.(gif|jpg|jpeg|png|webp|mp4|webm|mov|m4v)$/i.test(file.name)
    if (!supported) {
      toast.error("仅支持图片、GIF、MP4、WebM、MOV 素材")
      return
    }
    setUploadingField(field)
    try {
      const result = await uploadToolCover({
        file,
        toolName: form.toolName,
        toolCode: form.toolCode,
        modelName: field,
      })
      updateForm(field, result.url)
      toast.success("展示素材已上传")
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "素材上传失败")
    } finally {
      setUploadingField((current) => (current === field ? null : current))
    }
  }

  function handleFileChange(field: MediaField, event: ChangeEvent<HTMLInputElement>) {
    void uploadMedia(field, event.target.files?.[0])
    event.target.value = ""
  }

  function handleDrop(field: MediaField, event: DragEvent<HTMLLabelElement>) {
    event.preventDefault()
    setDraggingField(null)
    void uploadMedia(field, event.dataTransfer.files?.[0])
  }

  function mediaField(field: MediaField, label: string, placeholder: string) {
    const value = form[field]
    const inputId = `tool-media-${field}`
    const uploading = uploadingField === field
    const dragging = draggingField === field
    return (
      <div className="space-y-2">
        <Label>{label}</Label>
        <label
          htmlFor={inputId}
          className={cn(
            "flex min-h-40 cursor-pointer flex-col items-center justify-center overflow-hidden rounded-lg border border-dashed bg-background transition",
            dragging ? "border-primary bg-primary/5" : "border-border hover:border-primary/60 hover:bg-muted/30",
            uploading && "pointer-events-none opacity-70",
          )}
          onDragOver={(event) => {
            event.preventDefault()
            setDraggingField(field)
          }}
          onDragLeave={() => setDraggingField(null)}
          onDrop={(event) => handleDrop(field, event)}
        >
          <input
            id={inputId}
            className="sr-only"
            type="file"
            accept="image/*,video/mp4,video/webm,video/quicktime,video/x-m4v,.gif"
            onChange={(event) => handleFileChange(field, event)}
          />
          {uploading ? (
            <div className="flex flex-col items-center gap-2 text-sm text-muted-foreground">
              <Loader2 className="h-6 w-6 animate-spin" />
              正在上传...
            </div>
          ) : value ? (
            isVideoAsset(value) ? (
              <video src={value} className="h-full max-h-56 w-full object-cover" muted playsInline controls />
            ) : (
              <img src={value} alt="" className="h-full max-h-56 w-full object-cover" />
            )
          ) : (
            <div className="flex flex-col items-center gap-2 px-4 text-center text-sm text-muted-foreground">
              <UploadCloud className="h-8 w-8" />
              <span className="font-medium text-foreground">拖拽图片、GIF 或视频到这里</span>
              <span className="text-xs">也可以点击选择文件</span>
            </div>
          )}
        </label>
      </div>
    )
  }

  useEffect(() => {
    let cancelled = false
    async function load() {
      setLoading(true)
      setError(null)
      try {
        const configs = await fetchAgentModelConfigs()
        if (cancelled) return
        setModelConfigs(configs)

        if (toolId) {
          const detail = await fetchAdminToolDetail(toolId)
          if (cancelled) return
          const runtime = parseRuntimeConfig(detail.configNote)
          const kind = deriveKindFromTool(detail)
          setEditingTool(detail)
          setForm({
            toolKind: kind,
            toolCode: detail.toolCode,
            toolName: detail.toolName,
            description: detail.description || "",
            coverUrl: detail.coverUrl || "",
            systemPrompt: runtime.systemPrompt || "",
            adminPrompt: runtime.adminPrompt || stripRuntimeAndUi(detail.configNote),
            estimatedCreditCost: String(detail.estimatedCreditCost ?? 0),
            modelConfigId: detail.modelConfigId ? String(detail.modelConfigId) : "",
          })
        }
      } catch (err) {
        setError(err instanceof Error ? err.message : "加载配置失败")
      } finally {
        if (!cancelled) setLoading(false)
      }
    }
    void load()
    return () => {
      cancelled = true
    }
  }, [toolId])

  async function generatePromptDraft() {
    const toolName = form.toolName.trim()
    if (!toolName) {
      setError("请先填写工具名称，再使用 AI 自动填写 Prompt")
      return
    }
    setPromptGenerating(true)
    setError(null)
    try {
      const draft = await generateToolPromptDraft({
        modelConfigId: form.modelConfigId ? Number(form.modelConfigId) : undefined,
        toolName,
        description: form.description.trim(),
        toolKind: form.toolKind,
        coverUrl: form.coverUrl.trim(),
        userInputs,
      })
      setForm((current) => ({
        ...current,
        systemPrompt: draft.systemPrompt,
        adminPrompt: draft.toolPrompt,
      }))
      toast.success(draft.warning || `已使用 ${draft.modelName || "模型"} 自动填写 Prompt`)
    } catch (err) {
      setError(err instanceof Error ? err.message : "AI 自动填写 Prompt 失败")
    } finally {
      setPromptGenerating(false)
    }
  }

  async function save(publishAfterSave: boolean) {
    const toolName = form.toolName.trim()
    const adminPrompt = form.adminPrompt.trim()
    if (!toolName) {
      setError("请填写工具名称")
      return
    }
    if (!adminPrompt) {
      setError("请填写管理员提示词")
      return
    }

    setSaving(true)
    setError(null)
    try {
      const payload: UpsertToolPayload = {
        toolCode: form.toolCode.trim() || undefined,
        toolName,
        categoryId: null,
        description: form.description.trim(),
        coverUrl: form.coverUrl.trim(),
        toolType: execution.toolType,
        inputModality: execution.inputModality,
        outputModality: execution.outputModality,
        executionHandler: execution.executionHandler,
        configNote: serializeConfigNote(form),
        estimatedCreditCost: Number(form.estimatedCreditCost) || 0,
        modelConfigId: form.modelConfigId ? Number(form.modelConfigId) : null,
      }
      const saved = editingTool ? await updateTool(editingTool.id, payload) : await createTool(payload)
      await updateToolFields(saved.id, userInputs.map(toFieldPayload))

      if (publishAfterSave) {
        try {
          await publishTool(saved.id)
          toast.success("工具已保存并上线")
        } catch (publishError) {
          setEditingTool({ ...(editingTool || saved), fields: [] } as ToolDetail)
          setError(
            `工具已保存为草稿，但发布失败：${
              publishError instanceof Error ? publishError.message : "请检查模型能力和执行器配置"
            }`,
          )
          return
        }
      } else {
        toast.success("工具已保存")
      }
      router.push("/tools")
    } catch (err) {
      setError(err instanceof Error ? err.message : "保存失败")
    } finally {
      setSaving(false)
    }
  }

  return (
    <AdminLayout>
      <AdminHeader
        title={toolId ? "编辑 AI 工具" : "新建 AI 工具"}
        description="新的配置页只保留工具形态、用户输入、隐藏提示词、模型与展示素材。"
      />
      <main className="space-y-6 p-6">
        <Button variant="ghost" asChild>
          <Link href="/tools">
            <ArrowLeft className="mr-2 h-4 w-4" />
            返回工具配置
          </Link>
        </Button>

        {loading ? (
          <div className="flex h-72 items-center justify-center rounded-xl border bg-card text-sm text-muted-foreground">
            <Loader2 className="mr-2 h-4 w-4 animate-spin" />
            正在加载配置...
          </div>
        ) : (
          <div className="grid gap-6 xl:grid-cols-[minmax(0,1fr)_360px]">
            <div className="space-y-6">
              {error ? (
                <Alert variant="destructive">
                  <AlertTitle>配置未完成</AlertTitle>
                  <AlertDescription>{error}</AlertDescription>
                </Alert>
              ) : null}

              <section className="rounded-xl border bg-card p-5">
                <div className="mb-4">
                  <h2 className="text-base font-semibold">工具形态</h2>
                  <p className="text-sm text-muted-foreground">先选择用户端工具的输入形态，系统会自动带出字段和执行器。</p>
                </div>
                <div className="grid gap-3 md:grid-cols-2">
                  {kindOptions.map((item) => {
                    const Icon = item.icon
                    const active = form.toolKind === item.kind
                    return (
                      <button
                        key={item.kind}
                        type="button"
                        className={cn(
                          "rounded-lg border p-4 text-left transition",
                          active ? item.accent : "bg-background hover:bg-muted/50",
                        )}
                        onClick={() => updateForm("toolKind", item.kind)}
                      >
                        <div className="flex items-center gap-3">
                          <Icon className="h-5 w-5" />
                          <div>
                            <div className="font-medium">{item.title}</div>
                            <div className="mt-1 text-xs opacity-80">{item.desc}</div>
                          </div>
                        </div>
                      </button>
                    )
                  })}
                </div>
              </section>

              <section className="rounded-xl border bg-card p-5">
                <div className="mb-4">
                  <h2 className="text-base font-semibold">基础信息</h2>
                  <p className="text-sm text-muted-foreground">这些内容会展示在用户端工具卡片和详情页。</p>
                </div>
                <div className="grid gap-4 md:grid-cols-2">
                  <div className="space-y-2">
                    <Label>工具名称</Label>
                    <Input
                      value={form.toolName}
                      onChange={(event) => updateForm("toolName", event.target.value)}
                      placeholder="例如：AI 图像扩展器"
                    />
                  </div>
                  <div className="space-y-2">
                    <Label>工具编码</Label>
                    <Input
                      value={form.toolCode}
                      onChange={(event) => updateForm("toolCode", event.target.value)}
                      placeholder="例如：image_outpainting"
                    />
                  </div>
                  <div className="space-y-2 md:col-span-2">
                    <Label>工具描述</Label>
                    <Textarea
                      value={form.description}
                      onChange={(event) => updateForm("description", event.target.value)}
                      placeholder="给用户看的简短说明"
                      rows={3}
                    />
                  </div>
                  <div className="space-y-4 md:col-span-2">
                    <div className="max-w-xl">{mediaField("coverUrl", "封面素材", "拖拽或点击上传封面素材")}</div>
                    <p className="text-xs text-muted-foreground">
                      封面素材会用于用户端工具卡片展示，也会作为 AI 自动填写 Prompt 的视觉参考。
                    </p>
                  </div>
                </div>
              </section>

              <section className="rounded-xl border bg-card p-5">
                <div className="mb-4">
                  <h2 className="text-base font-semibold">用户输入</h2>
                  <p className="text-sm text-muted-foreground">首版不再让运营编辑字段 JSON，系统按工具形态自动生成。</p>
                </div>
                <div className="grid gap-3 md:grid-cols-2">
                  {userInputs.map((input) => (
                    <div key={input.fieldKey} className="rounded-lg border bg-background p-4">
                      <div className="flex items-center justify-between gap-3">
                        <div>
                          <div className="font-medium">{input.fieldName}</div>
                          <div className="mt-1 text-xs text-muted-foreground">{input.fieldKey}</div>
                        </div>
                        <Badge variant="outline">{input.fieldType}</Badge>
                      </div>
                      <p className="mt-3 text-sm text-muted-foreground">{input.placeholder}</p>
                      <div className="mt-3 text-xs text-muted-foreground">
                        {input.required ? "必填，会参与任务执行" : "选填，预留给后续能力"}
                      </div>
                    </div>
                  ))}
                </div>
              </section>

              <section className="rounded-xl border bg-card p-5">
                <div className="mb-4 flex flex-col gap-3 md:flex-row md:items-start md:justify-between">
                  <div>
                    <h2 className="text-base font-semibold">隐藏 Prompt</h2>
                    <p className="text-sm text-muted-foreground">
                      用户端不会看到这里。提交任务时，后端会把这段提示词和用户上传参数一起交给 Worker。
                    </p>
                  </div>
                  <Button type="button" variant="outline" onClick={generatePromptDraft} disabled={promptGenerating}>
                    {promptGenerating ? (
                      <Loader2 className="mr-2 h-4 w-4 animate-spin" />
                    ) : (
                      <WandSparkles className="mr-2 h-4 w-4" />
                    )}
                    AI 自动填写
                  </Button>
                </div>
                <div className="space-y-4">
                  <div className="space-y-2">
                    <Label>System Prompt</Label>
                    <Textarea
                      value={form.systemPrompt}
                      onChange={(event) => updateForm("systemPrompt", event.target.value)}
                      rows={3}
                      placeholder="可选：定义模型角色、输出格式和安全边界"
                    />
                  </div>
                  <div className="space-y-2">
                    <Label>工具 Prompt</Label>
                    <Textarea
                      value={form.adminPrompt}
                      onChange={(event) => updateForm("adminPrompt", event.target.value)}
                      rows={7}
                      placeholder="例如：扩展用户上传的图片 {{sourceImageUrl}}，保持原图主体、光照和透视一致..."
                    />
                    <p className="text-xs text-muted-foreground">
                      可引用用户字段：{userInputs.map((input) => `{{${input.fieldKey}}}`).join("、")}
                    </p>
                  </div>
                </div>
              </section>

              <section className="rounded-xl border bg-card p-5">
                <div className="mb-4">
                  <h2 className="text-base font-semibold">执行配置</h2>
                  <p className="text-sm text-muted-foreground">底层类型由工具形态自动推导，运营只需要选模型和价格。</p>
                </div>
                <div className="grid gap-4 md:grid-cols-2">
                  <div className="space-y-2">
                    <Label>模型配置</Label>
                    <Select value={form.modelConfigId || "none"} onValueChange={(value) => updateForm("modelConfigId", value === "none" ? "" : value)}>
                      <SelectTrigger>
                        <SelectValue placeholder="选择模型配置" />
                      </SelectTrigger>
                      <SelectContent>
                        <SelectItem value="none">暂不绑定模型</SelectItem>
                        {enabledModels.map((config) => (
                          <SelectItem key={config.id} value={String(config.id)}>
                            {displayName(config)}
                          </SelectItem>
                        ))}
                      </SelectContent>
                    </Select>
                  </div>
                  <div className="space-y-2">
                    <Label>消耗算力</Label>
                    <Input
                      type="number"
                      min={0}
                      value={form.estimatedCreditCost}
                      onChange={(event) => updateForm("estimatedCreditCost", event.target.value)}
                    />
                  </div>
                </div>
                <div className="mt-4 grid gap-3 rounded-lg border bg-background p-4 text-sm md:grid-cols-4">
                  <div>
                    <div className="text-xs text-muted-foreground">toolType</div>
                    <div className="mt-1 font-medium">{execution.toolType}</div>
                  </div>
                  <div>
                    <div className="text-xs text-muted-foreground">input</div>
                    <div className="mt-1 font-medium">{execution.inputModality}</div>
                  </div>
                  <div>
                    <div className="text-xs text-muted-foreground">output</div>
                    <div className="mt-1 font-medium">{execution.outputModality}</div>
                  </div>
                  <div>
                    <div className="text-xs text-muted-foreground">handler</div>
                    <div className="mt-1 font-medium">{execution.executionHandler}</div>
                  </div>
                </div>
              </section>
            </div>

            <aside className="space-y-4 xl:sticky xl:top-20 xl:self-start">
              <section className="rounded-xl border bg-card p-5">
                <div className="flex items-center gap-3">
                  <SelectedKindIcon className="h-5 w-5" />
                  <div>
                    <h2 className="font-semibold">配置预览</h2>
                    <p className="text-sm text-muted-foreground">{selectedKindMeta.title}</p>
                  </div>
                </div>
                <div className="mt-5 rounded-lg border bg-background p-4">
                  <div className="text-lg font-semibold">{form.toolName || "未命名工具"}</div>
                  <p className="mt-2 text-sm text-muted-foreground">{form.description || "暂无描述"}</p>
                  <div className="mt-4 rounded-lg border border-dashed bg-muted/40 p-5 text-center">
                    <UploadCloud className="mx-auto h-8 w-8 text-muted-foreground" />
                    <div className="mt-2 text-sm font-medium">{userInputs[0]?.fieldName || "输入内容"}</div>
                    <div className="mt-1 text-xs text-muted-foreground">{userInputs[0]?.placeholder}</div>
                  </div>
                  {form.coverUrl ? (
                    <div className="mt-4 overflow-hidden rounded-lg border">
                      {isVideoAsset(form.coverUrl) ? (
                        <video src={form.coverUrl} className="aspect-video w-full object-cover" muted playsInline controls />
                      ) : (
                        <img src={form.coverUrl} alt="" className="aspect-video w-full object-cover" />
                      )}
                    </div>
                  ) : null}
                </div>
                <div className="mt-4 space-y-2 text-sm">
                  <div className="flex items-center justify-between">
                    <span className="text-muted-foreground">字段数量</span>
                    <span>{userInputs.length}</span>
                  </div>
                  <div className="flex items-center justify-between">
                    <span className="text-muted-foreground">执行器</span>
                    <span>{execution.executionHandler}</span>
                  </div>
                  <div className="flex items-center justify-between">
                    <span className="text-muted-foreground">状态</span>
                    <span>{editingTool ? editingTool.status : "DRAFT"}</span>
                  </div>
                </div>
              </section>

              <section className="rounded-xl border bg-card p-5">
                <h2 className="font-semibold">保存</h2>
                <p className="mt-1 text-sm text-muted-foreground">
                  保存草稿不要求模型能力完全可用；上线时后端会校验模型和执行器。
                </p>
                <div className="mt-4 grid gap-2">
                  <Button onClick={() => save(false)} disabled={saving}>
                    {saving ? <Loader2 className="mr-2 h-4 w-4 animate-spin" /> : <Save className="mr-2 h-4 w-4" />}
                    保存草稿
                  </Button>
                  <Button variant="outline" onClick={() => save(true)} disabled={saving}>
                    <CheckCircle2 className="mr-2 h-4 w-4" />
                    保存并上线
                  </Button>
                </div>
              </section>
            </aside>
          </div>
        )}
      </main>
    </AdminLayout>
  )
}

export default function ConfigureToolPage() {
  return (
    <Suspense fallback={<div className="p-6 text-sm text-muted-foreground">正在打开配置页...</div>}>
      <ConfigurePageContent />
    </Suspense>
  )
}
