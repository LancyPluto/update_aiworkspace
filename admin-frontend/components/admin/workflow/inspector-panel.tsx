"use client"

import { useCallback, useMemo } from "react"
import { Copy, Plus, Trash2 } from "lucide-react"
import { FieldSchemaEditor, FieldSchemaPreview } from "@/components/admin/field-schema-editor"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select"
import { Textarea } from "@/components/ui/textarea"
import type { WorkflowNode, WorkflowNodeData, AgentModelConfig } from "@/lib/api/types"
import type { EditableField } from "@/lib/tool-fields"
import { NODE_TYPE_MAP, type NodeTypeDefinition } from "./node-registry"
import type { WorkflowSlot } from "./workflow-node"

const MODALITY_OPTIONS = [
  { value: "text", label: "文本" },
  { value: "image", label: "图片" },
  { value: "audio", label: "音频" },
  { value: "video", label: "视频" },
  { value: "file", label: "文件" },
  { value: "json", label: "JSON" },
  { value: "any", label: "任意" },
]

interface InspectorPanelProps {
  node: WorkflowNode | null
  modelConfigs: AgentModelConfig[]
  onUpdate: (nodeId: string, updates: Partial<WorkflowNodeData>) => void
  onDelete: (nodeId: string) => void
  onDuplicate: (nodeId: string) => void
  fieldDraft: EditableField[]
  fieldSaving: boolean
  fieldError: string | null
  onFieldDraftChange: (fields: EditableField[]) => void
  onSaveFields: () => void
  canDelete: boolean
  enableFieldEditor?: boolean
}

type SlotSide = "inputSlots" | "outputSlots"

function getNodeDef(node: WorkflowNode): NodeTypeDefinition | undefined {
  const data = node.data as WorkflowNodeData & { nodeDefType?: string }
  if (data.nodeDefType) return NODE_TYPE_MAP.get(data.nodeDefType)
  if (data.kind) return NODE_TYPE_MAP.get(data.kind)
  return undefined
}

function getSlots(
  data: (WorkflowNodeData & { inputSlots?: WorkflowSlot[]; outputSlots?: WorkflowSlot[] }) | undefined,
  def: NodeTypeDefinition | undefined,
  side: SlotSide,
): WorkflowSlot[] {
  const fallback = side === "inputSlots" ? def?.inputSlots : def?.outputSlots
  return (data?.[side] || fallback || []) as WorkflowSlot[]
}

function makeUniqueName(slots: WorkflowSlot[], base = "param"): string {
  let index = slots.length + 1
  let name = `${base}_${index}`
  const existing = new Set(slots.map((slot) => slot.name))
  while (existing.has(name)) {
    index += 1
    name = `${base}_${index}`
  }
  return name
}

function modelOptionText(model: AgentModelConfig): string {
  const label = model.displayName || model.configCode || model.modelName
  return `${label} / ${model.provider} / ${model.modelName}`
}

export function InspectorPanel({
  node,
  modelConfigs,
  onUpdate,
  onDelete,
  onDuplicate,
  fieldDraft,
  fieldSaving,
  fieldError,
  onFieldDraftChange,
  onSaveFields,
  canDelete,
  enableFieldEditor = true,
}: InspectorPanelProps) {
  const def = node ? getNodeDef(node) : undefined
  const data = node?.data as (WorkflowNodeData & {
    nodeDefType?: string
    inputSlots?: WorkflowSlot[]
    outputSlots?: WorkflowSlot[]
  }) | undefined

  const inputSlots = getSlots(data, def, "inputSlots")
  const outputSlots = getSlots(data, def, "outputSlots")

  const updateField = useCallback(
    (key: string, value: unknown) => {
      if (!node) return
      onUpdate(node.id, { [key]: value } as Partial<WorkflowNodeData>)
    },
    [node, onUpdate],
  )

  const updateParam = useCallback(
    (paramName: string, value: unknown) => {
      if (!node) return
      const params = { ...(data?.parameters || {}) }
      params[paramName] = value
      onUpdate(node.id, { parameters: params } as Partial<WorkflowNodeData>)
    },
    [node, data, onUpdate],
  )

  const updateSlots = useCallback(
    (side: SlotSide, slots: WorkflowSlot[]) => {
      if (!node) return
      onUpdate(node.id, { [side]: slots } as Partial<WorkflowNodeData>)
    },
    [node, onUpdate],
  )

  const modelOptions = useMemo(
    () => modelConfigs.filter((config) => config.enabled !== false),
    [modelConfigs],
  )

  const selectedModel = useMemo(() => {
    const id = Number(data?.parameters?.modelConfigId)
    if (!Number.isFinite(id)) return null
    return modelConfigs.find((config) => config.id === id) || null
  }, [data?.parameters?.modelConfigId, modelConfigs])

  return (
    <aside className="flex h-full flex-col">
      <div className="shrink-0 border-b border-border p-3">
        <p className="text-sm font-semibold text-card-foreground">节点属性</p>
      </div>

      <div className="flex-1 space-y-4 overflow-y-auto p-3">
        {!node || !data ? (
          <div className="py-8 text-center">
            <p className="text-xs text-muted-foreground">点击画布中的节点进行编辑</p>
          </div>
        ) : (
          <>
            <div className="flex items-center gap-2">
              <div
                className="h-3 w-3 rounded-full"
                style={{ backgroundColor: def?.color || "#64748b" }}
              />
              <Badge variant="secondary" className="text-[10px]">
                {def?.category || "节点"}
              </Badge>
              <Badge variant="outline" className="text-[10px]">
                {def?.displayName || data.title}
              </Badge>
            </div>

            <TextControl label="节点名称" value={data.title || ""} onChange={(value) => updateField("title", value)} />

            <div className="space-y-1.5">
              <Label className="text-xs">说明</Label>
              <Textarea
                value={data.detail || ""}
                onChange={(event) => updateField("detail", event.target.value)}
                className="min-h-[60px] text-xs"
              />
            </div>

            {def?.parameters && def.parameters.length > 0 ? (
              <div className="space-y-3">
                <p className="text-xs font-semibold text-muted-foreground">节点配置</p>
                {def.type === "llm_model" ? (
                  <div className="rounded-md border border-border bg-secondary/20 px-2.5 py-2 text-xs">
                    <p className="font-medium text-card-foreground">当前模型</p>
                    <p className="mt-1 leading-4 text-muted-foreground">
                      {selectedModel ? modelOptionText(selectedModel) : "未选择，请从系统配置中选择模型。"}
                    </p>
                  </div>
                ) : null}
                {def.parameters.map((param) => (
                  <ParamField
                    key={param.name}
                    param={param}
                    value={data.parameters?.[param.name] ?? param.default}
                    onChange={(value) => updateParam(param.name, value)}
                    modelOptions={modelOptions}
                  />
                ))}
              </div>
            ) : null}

            {def?.type === "field_input" && enableFieldEditor ? (
              <div className="space-y-3 rounded-lg border border-border bg-secondary/20 p-3">
                <div>
                  <p className="text-xs font-semibold text-card-foreground">用户侧表单字段</p>
                  <p className="mt-1 text-[11px] leading-4 text-muted-foreground">
                    这里配置的字段会同步成为输入节点的输出参数。
                  </p>
                </div>
                {fieldError ? (
                  <div className="rounded-md border border-destructive/30 bg-destructive/5 px-2.5 py-2 text-xs text-destructive">
                    {fieldError}
                  </div>
                ) : null}
                <FieldSchemaEditor
                  fields={fieldDraft}
                  disabled={fieldSaving}
                  onChange={onFieldDraftChange}
                />
                <FieldSchemaPreview fields={fieldDraft} />
                <Button
                  type="button"
                  size="sm"
                  className="w-full text-xs"
                  onClick={onSaveFields}
                  disabled={fieldSaving}
                >
                  {fieldSaving ? "保存中..." : "保存字段配置"}
                </Button>
              </div>
            ) : def?.type === "field_input" ? (
              <div className="rounded-lg border border-dashed border-border bg-secondary/10 p-3 text-[11px] leading-5 text-muted-foreground">
                工作流类工具不在此编辑表单字段。初始参数由配置包或「工具详情」维护；分步意见由画布中的「用户补充输入」节点承接。
              </div>
            ) : def?.type === "user_input" ? (
              <div className="space-y-3 rounded-lg border border-border bg-secondary/20 p-3">
                <p className="text-xs font-semibold text-card-foreground">分步用户意见节点</p>
                <p className="text-[11px] leading-4 text-muted-foreground">
                  用户在工作台对应阶段填写「{String(data.parameters?.fieldKey || "意见")}」。留空则沿用上一步结果继续执行。
                </p>
                <SlotEditor
                  title="输出参数"
                  slots={outputSlots}
                  onChange={(slots) => updateSlots("outputSlots", slots)}
                />
              </div>
            ) : (
              <>
                <SlotEditor
                  title="输入参数"
                  slots={inputSlots}
                  onChange={(slots) => updateSlots("inputSlots", slots)}
                />
                <SlotEditor
                  title="输出参数"
                  slots={outputSlots}
                  onChange={(slots) => updateSlots("outputSlots", slots)}
                />
              </>
            )}
          </>
        )}
      </div>

      {node && data ? (
        <div className="shrink-0 space-y-2 border-t border-border p-3">
          <Button
            variant="outline"
            size="sm"
            className="w-full gap-2 text-xs"
            onClick={() => onDuplicate(node.id)}
          >
            <Copy className="h-3.5 w-3.5" />
            复制节点
          </Button>
          {canDelete ? (
            <Button
              variant="outline"
              size="sm"
              className="w-full gap-2 text-xs text-destructive hover:text-destructive"
              onClick={() => onDelete(node.id)}
            >
              <Trash2 className="h-3.5 w-3.5" />
              删除节点
            </Button>
          ) : null}
        </div>
      ) : null}
    </aside>
  )
}

function TextControl({
  label,
  value,
  onChange,
}: {
  label: string
  value: string
  onChange: (value: string) => void
}) {
  return (
    <div className="space-y-1.5">
      <Label className="text-xs">{label}</Label>
      <Input value={value} onChange={(event) => onChange(event.target.value)} className="h-8 text-xs" />
    </div>
  )
}

function SlotEditor({
  title,
  slots,
  onChange,
}: {
  title: string
  slots: WorkflowSlot[]
  onChange: (slots: WorkflowSlot[]) => void
}) {
  function addSlot() {
    const name = makeUniqueName(slots)
    onChange([...slots, { name, label: "新参数", type: "text" }])
  }

  function updateSlot(index: number, updates: Partial<WorkflowSlot>) {
    const next = slots.map((slot, currentIndex) =>
      currentIndex === index ? { ...slot, ...updates } : slot,
    )
    onChange(next)
  }

  function removeSlot(index: number) {
    onChange(slots.filter((_, currentIndex) => currentIndex !== index))
  }

  return (
    <div className="space-y-3 rounded-lg border border-border bg-secondary/20 p-3">
      <div className="flex items-center justify-between gap-2">
        <p className="text-xs font-semibold text-card-foreground">{title}</p>
        <Button type="button" size="icon" variant="outline" className="h-7 w-7" onClick={addSlot}>
          <Plus className="h-3.5 w-3.5" />
        </Button>
      </div>

      {slots.length === 0 ? (
        <p className="rounded-md border border-dashed border-border px-2.5 py-3 text-center text-xs text-muted-foreground">
          暂无参数
        </p>
      ) : (
        <div className="space-y-2">
          {slots.map((slot, index) => (
            <div key={`${slot.name}-${index}`} className="grid grid-cols-[1fr_100px_auto] gap-2 rounded-md border border-border/70 bg-background p-2">
              <Input
                value={slot.label}
                onChange={(event) => updateSlot(index, { label: event.target.value })}
                placeholder="参数中文名称"
                className="h-8 text-xs"
              />
              <Select value={slot.type} onValueChange={(value) => updateSlot(index, { type: value })}>
                <SelectTrigger className="h-8 text-xs">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {MODALITY_OPTIONS.map((option) => (
                    <SelectItem key={option.value} value={option.value}>
                      {option.label}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
              <Button
                type="button"
                size="icon"
                variant="ghost"
                className="h-8 w-8 text-destructive hover:text-destructive"
                onClick={() => removeSlot(index)}
              >
                <Trash2 className="h-3.5 w-3.5" />
              </Button>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}

interface ParamFieldProps {
  param: NonNullable<NodeTypeDefinition["parameters"]>[number]
  value: unknown
  onChange: (value: unknown) => void
  modelOptions: AgentModelConfig[]
}

function ParamField({ param, value, onChange, modelOptions }: ParamFieldProps) {
  switch (param.type) {
    case "boolean":
      return (
        <div className="flex items-center justify-between">
          <Label className="text-xs">{param.label}</Label>
          <Select value={value === true ? "true" : "false"} onValueChange={(next) => onChange(next === "true")}>
            <SelectTrigger className="h-8 w-24 text-xs">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="true">True</SelectItem>
              <SelectItem value="false">False</SelectItem>
            </SelectContent>
          </Select>
        </div>
      )

    case "number":
      return (
        <div className="space-y-1.5">
          <Label className="text-xs">{param.label}</Label>
          <Input
            type="number"
            value={String(value ?? "")}
            min={param.min}
            step={param.step}
            onChange={(event) => onChange(event.target.value === "" ? undefined : Number(event.target.value))}
            className="h-8 text-xs"
          />
          {param.description ? (
            <p className="text-[11px] leading-4 text-muted-foreground">{param.description}</p>
          ) : null}
        </div>
      )

    case "select":
      return (
        <div className="space-y-1.5">
          <Label className="text-xs">{param.label}</Label>
          <Select value={String(value ?? "")} onValueChange={(next) => onChange(next)}>
            <SelectTrigger className="h-8 w-full text-xs">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {(param.options || []).map((option) => (
                <SelectItem key={option.value} value={option.value}>
                  {option.label}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
          {param.description ? (
            <p className="text-[11px] leading-4 text-muted-foreground">{param.description}</p>
          ) : null}
        </div>
      )

    case "model_selector":
      return (
        <div className="space-y-1.5">
          <Label className="text-xs">{param.label}</Label>
          <Select
            value={String(value ?? "__none")}
            onValueChange={(next) => onChange(next === "__none" ? null : Number(next))}
          >
            <SelectTrigger className="h-8 w-full text-xs">
              <SelectValue placeholder="选择模型" />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="__none">不绑定</SelectItem>
              {modelOptions.map((model) => (
                <SelectItem key={model.id} value={String(model.id)}>
                  {modelOptionText(model)}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>
      )

    default:
      return (
        <div className="space-y-1.5">
          <Label className="text-xs">{param.label}</Label>
          <Input
            value={String(value ?? "")}
            onChange={(event) => onChange(event.target.value)}
            className="h-8 text-xs"
          />
        </div>
      )
  }
}
