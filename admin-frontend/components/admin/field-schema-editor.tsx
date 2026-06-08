"use client"

import { useMemo, useState } from "react"
import { FileUp, ImageUp, Plus, Trash2 } from "lucide-react"
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
import { Switch } from "@/components/ui/switch"
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs"
import {
  filterFieldsByTier,
  groupFields,
  isCustomModeAdvanced,
  isFieldVisible,
  type FieldUiMeta,
} from "@/lib/field-ui-meta"
import {
  FIELD_TYPE_OPTIONS,
  OPTION_PRESETS,
  type EditableField,
  type FieldTypeValue,
  type OptionPresetKey,
  supportsOptions,
  applyOptionPreset,
  createEmptyField,
  detectPreset,
} from "@/lib/tool-fields"

type FieldSchemaEditorProps = {
  fields: EditableField[]
  onChange: (fields: EditableField[]) => void
  disabled?: boolean
}

const AGENT_FILL_STRATEGIES: Array<{ value: EditableField["agentFillStrategy"]; label: string; hint: string }> = [
  { value: "infer_from_user", label: "从用户需求推断", hint: "适合主题、提示词、对象等可从自然语言抽取的字段" },
  { value: "default", label: "使用默认值", hint: "适合比例、数量、画质等有产品默认值的字段" },
  { value: "ask_user", label: "缺失时追问", hint: "适合预算、授权、账号等不能擅自决定的字段" },
  { value: "derive", label: "由系统派生", hint: "适合 userId、sessionId、素材 ID 等上下文字段" },
  { value: "none", label: "不参与 Agent 填充", hint: "保留给后台或工具执行层处理" },
]

const RISK_LEVELS: Array<{ value: EditableField["riskLevel"]; label: string }> = [
  { value: "LOW", label: "低风险" },
  { value: "MEDIUM", label: "中风险" },
  { value: "HIGH", label: "高风险" },
]

export function FieldSchemaEditor({ fields, onChange, disabled }: FieldSchemaEditorProps) {
  function updateField(index: number, patch: Partial<EditableField>) {
    const next = fields.map((field, i) => {
      if (i !== index) return field
      const merged = { ...field, ...patch }
      if (patch.fieldType && !supportsOptions(patch.fieldType)) {
        merged.options = []
      }
      return merged
    }).map((field, i) => (patch.isCore === true && i !== index ? { ...field, isCore: false } : field))
    onChange(next)
  }

  function updateOption(fieldIndex: number, optionIndex: number, patch: Partial<{ label: string; value: string; promptPrefix: string }>) {
    const field = fields[fieldIndex]
    const options = field.options.map((row, i) => (i === optionIndex ? { ...row, ...patch } : row))
    updateField(fieldIndex, { options })
  }

  function addOption(fieldIndex: number) {
    const field = fields[fieldIndex]
    updateField(fieldIndex, { options: [...field.options, { label: "", value: "" }] })
  }

  function removeOption(fieldIndex: number, optionIndex: number) {
    const field = fields[fieldIndex]
    updateField(fieldIndex, { options: field.options.filter((_, i) => i !== optionIndex) })
  }

  function removeField(index: number) {
    onChange(
      fields
        .filter((_, i) => i !== index)
        .map((field, i) => ({ ...field, sortOrder: i + 1 })),
    )
  }

  function moveField(index: number, direction: -1 | 1) {
    const target = index + direction
    if (target < 0 || target >= fields.length) return
    const next = [...fields]
    const [item] = next.splice(index, 1)
    next.splice(target, 0, item)
    onChange(next.map((field, i) => ({ ...field, sortOrder: i + 1 })))
  }

  function updateUiMeta(index: number, patch: Partial<FieldUiMeta>) {
    const field = fields[index]
    updateField(index, { uiMeta: { ...field.uiMeta, ...patch } })
  }

  function addVisibleWhenRule(index: number) {
    const field = fields[index]
    const visibleWhen = { ...(field.uiMeta.visibleWhen || {}), "": [""] }
    updateUiMeta(index, { visibleWhen })
  }

  function updateVisibleWhenKey(index: number, oldKey: string, newKey: string) {
    const field = fields[index]
    const visibleWhen = { ...(field.uiMeta.visibleWhen || {}) }
    const values = visibleWhen[oldKey] || [""]
    delete visibleWhen[oldKey]
    if (newKey.trim()) visibleWhen[newKey.trim()] = values
    updateUiMeta(index, { visibleWhen: Object.keys(visibleWhen).length ? visibleWhen : undefined })
  }

  function updateVisibleWhenValues(index: number, key: string, raw: string) {
    const field = fields[index]
    const visibleWhen = { ...(field.uiMeta.visibleWhen || {}) }
    visibleWhen[key] = raw.split(",").map((item) => item.trim()).filter(Boolean)
    updateUiMeta(index, { visibleWhen })
  }

  function removeVisibleWhenRule(index: number, key: string) {
    const field = fields[index]
    const visibleWhen = { ...(field.uiMeta.visibleWhen || {}) }
    delete visibleWhen[key]
    updateUiMeta(index, { visibleWhen: Object.keys(visibleWhen).length ? visibleWhen : undefined })
  }

  return (
    <div className="space-y-4">
        {fields.length === 0 ? (
          <p className="rounded-lg border border-dashed border-border py-10 text-center text-sm text-muted-foreground">
            暂无字段。点击下方「添加字段」，或先「应用模板」。
          </p>
        ) : null}

        {fields.map((field, index) => {
          const preset = detectPreset(field)
          return (
            <div key={`${field.fieldKey}-${index}`} className="space-y-3 rounded-lg border border-border bg-secondary/20 p-4">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <p className="text-sm font-medium">字段 {index + 1}</p>
                <div className="flex flex-wrap gap-1">
                  <Button type="button" variant="ghost" size="sm" disabled={disabled || index === 0} onClick={() => moveField(index, -1)}>
                    上移
                  </Button>
                  <Button
                    type="button"
                    variant="ghost"
                    size="sm"
                    disabled={disabled || index === fields.length - 1}
                    onClick={() => moveField(index, 1)}
                  >
                    下移
                  </Button>
                  <Button type="button" variant="ghost" size="sm" className="text-destructive" disabled={disabled} onClick={() => removeField(index)}>
                    <Trash2 className="h-4 w-4" />
                  </Button>
                </div>
              </div>

              <div className="grid gap-3 sm:grid-cols-2">
                <div className="space-y-1.5">
                  <Label>字段键 fieldKey</Label>
                  <Input
                    value={field.fieldKey}
                    disabled={disabled}
                    placeholder="如 aspectRatio"
                    onChange={(e) => updateField(index, { fieldKey: e.target.value })}
                  />
                </div>
                <div className="space-y-1.5">
                  <Label>显示名</Label>
                  <Input
                    value={field.fieldName}
                    disabled={disabled}
                    placeholder="如 画面比例"
                    onChange={(e) => updateField(index, { fieldName: e.target.value })}
                  />
                </div>
                <div className="space-y-1.5">
                  <Label>控件类型</Label>
                  <Select
                    value={field.fieldType}
                    disabled={disabled}
                    onValueChange={(value) => updateField(index, { fieldType: value as FieldTypeValue })}
                  >
                    <SelectTrigger>
                      <SelectValue />
                    </SelectTrigger>
                    <SelectContent>
                      {FIELD_TYPE_OPTIONS.map((item) => (
                        <SelectItem key={item.value} value={item.value}>
                          {item.label}
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                  <p className="text-[11px] text-muted-foreground">
                    {FIELD_TYPE_OPTIONS.find((item) => item.value === field.fieldType)?.hint}
                  </p>
                </div>
                <div className="flex items-end gap-3 pb-1">
                  <div className="flex items-center gap-2">
                    <Switch
                      checked={field.required}
                      disabled={disabled}
                      onCheckedChange={(checked) => updateField(index, { required: checked, executionRequired: checked })}
                    />
                    <Label className="text-sm">接口必填</Label>
                  </div>
                  <div className="flex items-center gap-2">
                    <Switch
                      checked={field.isCore}
                      disabled={disabled}
                      onCheckedChange={(checked) => updateField(index, { isCore: checked })}
                    />
                    <Label className="text-sm">核心字段</Label>
                  </div>
                </div>
              </div>
              {field.isCore ? (
                <p className="rounded-md border border-primary/20 bg-primary/5 px-3 py-2 text-xs text-primary">
                  核心字段会替代用户端底部主输入框；同一个工具只能选择一个，适合 prompt / 视频描述 / 生成需求。
                </p>
              ) : null}

              <div className="space-y-1.5">
                <Label>占位提示 placeholder</Label>
                <Input
                  value={field.placeholder}
                  disabled={disabled}
                  placeholder="用户未填写时看到的说明"
                  onChange={(e) => updateField(index, { placeholder: e.target.value })}
                />
              </div>

              <div className="space-y-3 rounded-md border border-primary/15 bg-primary/5 p-3">
                <div>
                  <p className="text-sm font-medium">Agent 交互策略</p>
                  <p className="mt-1 text-[11px] leading-5 text-muted-foreground">
                    区分“工具执行必须有”和“必须追问用户”。默认值、上下文派生和可推断字段不会打断对话。
                  </p>
                </div>
                <div className="grid gap-3 lg:grid-cols-2">
                  <div className="flex items-center justify-between rounded-md border border-border/80 bg-background/70 px-3 py-2">
                    <div>
                      <Label className="text-sm">执行必填</Label>
                      <p className="text-[11px] text-muted-foreground">调用工具前必须有值，会进入 JSON schema required。</p>
                    </div>
                    <Switch
                      checked={field.executionRequired}
                      disabled={disabled}
                      onCheckedChange={(checked) => updateField(index, { executionRequired: checked, required: checked ? field.required : false })}
                    />
                  </div>
                  <div className="flex items-center justify-between rounded-md border border-border/80 bg-background/70 px-3 py-2">
                    <div>
                      <Label className="text-sm">缺失时追问用户</Label>
                      <p className="text-[11px] text-muted-foreground">只给真正需要用户决策的字段打开。</p>
                    </div>
                    <Switch
                      checked={field.userRequired}
                      disabled={disabled}
                      onCheckedChange={(checked) => updateField(index, {
                        userRequired: checked,
                        agentFillStrategy: checked ? "ask_user" : field.agentFillStrategy === "ask_user" ? "default" : field.agentFillStrategy,
                      })}
                    />
                  </div>
                  <div className="space-y-1.5">
                    <Label>填充策略</Label>
                    <Select
                      value={field.agentFillStrategy}
                      disabled={disabled}
                      onValueChange={(value) => {
                        const strategy = value as EditableField["agentFillStrategy"]
                        updateField(index, {
                          agentFillStrategy: strategy,
                          userRequired: strategy === "ask_user" ? true : field.userRequired && strategy !== "default" && strategy !== "derive" && strategy !== "none",
                        })
                      }}
                    >
                      <SelectTrigger>
                        <SelectValue />
                      </SelectTrigger>
                      <SelectContent>
                        {AGENT_FILL_STRATEGIES.map((item) => (
                          <SelectItem key={item.value} value={item.value}>
                            {item.label}
                          </SelectItem>
                        ))}
                      </SelectContent>
                    </Select>
                    <p className="text-[11px] text-muted-foreground">
                      {AGENT_FILL_STRATEGIES.find((item) => item.value === field.agentFillStrategy)?.hint}
                    </p>
                  </div>
                  <div className="space-y-1.5">
                    <Label>风险等级</Label>
                    <Select
                      value={field.riskLevel}
                      disabled={disabled}
                      onValueChange={(value) => updateField(index, { riskLevel: value as EditableField["riskLevel"] })}
                    >
                      <SelectTrigger>
                        <SelectValue />
                      </SelectTrigger>
                      <SelectContent>
                        {RISK_LEVELS.map((item) => (
                          <SelectItem key={item.value} value={item.value}>
                            {item.label}
                          </SelectItem>
                        ))}
                      </SelectContent>
                    </Select>
                    <p className="text-[11px] text-muted-foreground">高风险字段后续可接二次确认、审计和权限策略。</p>
                  </div>
                  <div className="space-y-1.5 lg:col-span-2">
                    <Label>默认值</Label>
                    <Input
                      value={field.defaultValue}
                      disabled={disabled}
                      placeholder="如 3:4、1、standard；无默认值则留空"
                      onChange={(e) => updateField(index, { defaultValue: e.target.value })}
                    />
                    <p className="text-[11px] text-muted-foreground">
                      当填充策略为“使用默认值”时，Agent 会优先使用这里的值，不再追问用户。
                    </p>
                  </div>
                </div>
              </div>

              {supportsOptions(field.fieldType) ? (
                <div className="space-y-3 rounded-md border border-border/80 bg-background/50 p-3">
                  <div className="flex flex-wrap items-end gap-3">
                    <div className="min-w-[200px] flex-1 space-y-1.5">
                      <Label>选项预设</Label>
                      <Select
                        value={preset || "custom"}
                        disabled={disabled}
                        onValueChange={(value) => {
                          if (value === "custom") return
                          updateField(index, applyOptionPreset(field, value as OptionPresetKey))
                        }}
                      >
                        <SelectTrigger>
                          <SelectValue placeholder="选择常用选项组" />
                        </SelectTrigger>
                        <SelectContent>
                          <SelectItem value="custom">自定义</SelectItem>
                          {(Object.entries(OPTION_PRESETS) as Array<[OptionPresetKey, (typeof OPTION_PRESETS)[OptionPresetKey]]>).map(
                            ([key, item]) => (
                              <SelectItem key={key} value={key}>
                                {item.label}
                              </SelectItem>
                            ),
                          )}
                        </SelectContent>
                      </Select>
                    </div>
                    <Button type="button" variant="outline" size="sm" disabled={disabled} onClick={() => addOption(index)}>
                      <Plus className="mr-1 h-3.5 w-3.5" />
                      添加选项
                    </Button>
                  </div>
                  <p className="text-[11px] text-muted-foreground">
                    用户端将显示为{field.fieldType === "aspect_ratio" ? "画面比例控件" : field.fieldType === "radio" ? "单选按钮" : "下拉框"}，只能在这些值中选择，无需手输比例。
                    文生图风格可配置提示词前缀；“无”使用 __none__，“自定义”使用 __custom__。
                  </p>
                  {field.options.length === 0 ? (
                    <p className="text-xs text-amber-600 dark:text-amber-400">请添加至少一个选项，或选择上方预设。</p>
                  ) : (
                    <div className="space-y-2">
                      {field.options.map((opt, optIndex) => (
                        <div key={optIndex} className="grid grid-cols-[1fr_1fr_auto] gap-2">
                          <Input
                            value={opt.label}
                            disabled={disabled}
                            placeholder="展示文案"
                            onChange={(e) => updateOption(index, optIndex, { label: e.target.value })}
                          />
                          <Input
                            value={opt.value}
                            disabled={disabled}
                            placeholder="提交给 API 的值"
                            onChange={(e) => updateOption(index, optIndex, { value: e.target.value })}
                          />
                          <Button
                            type="button"
                            variant="ghost"
                            size="icon"
                            disabled={disabled}
                            onClick={() => removeOption(index, optIndex)}
                          >
                            <Trash2 className="h-4 w-4" />
                          </Button>
                          <Input
                            value={opt.promptPrefix || ""}
                            disabled={disabled}
                            placeholder="可选：选择该项时拼到提示词前面的风格提示词"
                            className="col-span-3"
                            onChange={(e) => updateOption(index, optIndex, { promptPrefix: e.target.value })}
                          />
                        </div>
                      ))}
                    </div>
                  )}
                </div>
              ) : null}

              <div className="space-y-3 rounded-md border border-border/80 bg-background/50 p-3">
                <div>
                  <p className="text-sm font-medium">用户端展示元数据</p>
                  <p className="mt-1 text-[11px] leading-5 text-muted-foreground">
                    控制常规/高级分层、分组折叠与条件显隐；写入 optionsJson，与用户端 DynamicForm 共用。
                  </p>
                </div>
                <div className="grid gap-3 sm:grid-cols-2">
                  <div className="space-y-1.5">
                    <Label>展示分层 uiTier</Label>
                    <Select
                      value={field.uiMeta.uiTier || "all"}
                      disabled={disabled}
                      onValueChange={(value) =>
                        updateUiMeta(index, { uiTier: value === "all" ? undefined : (value as FieldUiMeta["uiTier"]) })
                      }
                    >
                      <SelectTrigger>
                        <SelectValue />
                      </SelectTrigger>
                      <SelectContent>
                        <SelectItem value="all">全部模式</SelectItem>
                        <SelectItem value="simple">仅常规</SelectItem>
                        <SelectItem value="advanced">仅高级</SelectItem>
                      </SelectContent>
                    </Select>
                  </div>
                  <div className="space-y-1.5">
                    <Label>分组键 uiGroup</Label>
                    <Input
                      value={field.uiMeta.uiGroup || ""}
                      disabled={disabled}
                      placeholder="如 lyrics / styles / more"
                      onChange={(e) => updateUiMeta(index, { uiGroup: e.target.value.trim() || undefined })}
                    />
                  </div>
                  <div className="space-y-1.5">
                    <Label>分组标题 uiGroupLabel</Label>
                    <Input
                      value={field.uiMeta.uiGroupLabel || ""}
                      disabled={disabled}
                      placeholder="如 歌词 / 风格 / 更多选项"
                      onChange={(e) => updateUiMeta(index, { uiGroupLabel: e.target.value.trim() || undefined })}
                    />
                  </div>
                  <div className="space-y-1.5">
                    <Label>最大字数 maxLength</Label>
                    <Input
                      type="number"
                      value={field.uiMeta.maxLength ?? ""}
                      disabled={disabled}
                      placeholder="如 500"
                      onChange={(e) =>
                        updateUiMeta(index, {
                          maxLength: e.target.value.trim() ? Number(e.target.value) : undefined,
                        })
                      }
                    />
                  </div>
                </div>
                {field.fieldType === "slider" ? (
                  <div className="grid gap-3 sm:grid-cols-3">
                    <div className="space-y-1.5">
                      <Label>滑块最小值</Label>
                      <Input
                        type="number"
                        value={field.uiMeta.slider?.min ?? 0}
                        disabled={disabled}
                        onChange={(e) =>
                          updateUiMeta(index, {
                            slider: {
                              min: Number(e.target.value),
                              max: field.uiMeta.slider?.max ?? 1,
                              step: field.uiMeta.slider?.step ?? 0.01,
                            },
                          })
                        }
                      />
                    </div>
                    <div className="space-y-1.5">
                      <Label>滑块最大值</Label>
                      <Input
                        type="number"
                        value={field.uiMeta.slider?.max ?? 1}
                        disabled={disabled}
                        onChange={(e) =>
                          updateUiMeta(index, {
                            slider: {
                              min: field.uiMeta.slider?.min ?? 0,
                              max: Number(e.target.value),
                              step: field.uiMeta.slider?.step ?? 0.01,
                            },
                          })
                        }
                      />
                    </div>
                    <div className="space-y-1.5">
                      <Label>步进 step</Label>
                      <Input
                        type="number"
                        value={field.uiMeta.slider?.step ?? 0.01}
                        disabled={disabled}
                        onChange={(e) =>
                          updateUiMeta(index, {
                            slider: {
                              min: field.uiMeta.slider?.min ?? 0,
                              max: field.uiMeta.slider?.max ?? 1,
                              step: Number(e.target.value),
                            },
                          })
                        }
                      />
                    </div>
                  </div>
                ) : null}
                <div className="space-y-2">
                  <div className="flex items-center justify-between">
                    <Label>条件显隐 visibleWhen</Label>
                    <Button type="button" variant="outline" size="sm" disabled={disabled} onClick={() => addVisibleWhenRule(index)}>
                      添加条件
                    </Button>
                  </div>
                  {Object.entries(field.uiMeta.visibleWhen || {}).map(([depKey, values]) => (
                    <div key={`${depKey}-${index}`} className="grid gap-2 sm:grid-cols-[1fr_1fr_auto]">
                      <Input
                        value={depKey}
                        disabled={disabled}
                        placeholder="依赖字段，如 customMode"
                        onChange={(e) => updateVisibleWhenKey(index, depKey, e.target.value)}
                      />
                      <Input
                        value={values.join(", ")}
                        disabled={disabled}
                        placeholder="允许值，逗号分隔，如 true 或 false"
                        onChange={(e) => updateVisibleWhenValues(index, depKey, e.target.value)}
                      />
                      <Button type="button" variant="ghost" size="icon" disabled={disabled} onClick={() => removeVisibleWhenRule(index, depKey)}>
                        <Trash2 className="h-4 w-4" />
                      </Button>
                    </div>
                  ))}
                </div>
              </div>
            </div>
          )
        })}

        <Button
          type="button"
          variant="outline"
          className="w-full"
          disabled={disabled}
          onClick={() => onChange([...fields, createEmptyField(fields.length + 1)])}
        >
          <Plus className="mr-2 h-4 w-4" />
          添加字段
        </Button>
    </div>
  )
}

export function FieldSchemaPreview({ fields }: { fields: EditableField[] }) {
  const [previewMode, setPreviewMode] = useState<"simple" | "advanced">("simple")
  const previewValues = useMemo(() => buildPreviewValues(fields), [fields])
  const values = useMemo(
    () => ({
      ...previewValues,
      customMode: previewMode === "advanced" ? "true" : "false",
    }),
    [previewMode, previewValues],
  )

  const visibleFields = useMemo(() => {
    const advanced = isCustomModeAdvanced(values)
    const tierFiltered = filterFieldsByTier(
      fields.map((field) => ({ fieldKey: field.fieldKey, meta: field.uiMeta })),
      advanced,
    )
    const tierKeys = new Set(tierFiltered.map((item) => item.fieldKey))
    return fields.filter(
      (field) =>
        tierKeys.has(field.fieldKey) &&
        !field.isCore &&
        isFieldVisible(field.fieldKey, field.uiMeta, values),
    )
  }, [fields, values])

  const grouped = useMemo(
    () =>
      groupFields(
        visibleFields.map((field) => ({
          fieldKey: field.fieldKey,
          meta: field.uiMeta,
          field,
        })),
      ),
    [visibleFields],
  )

  return (
    <div>
      <Tabs value={previewMode} onValueChange={(value) => setPreviewMode(value as "simple" | "advanced")}>
        <div className="mb-3 flex items-center justify-between gap-2">
          <TabsList>
            <TabsTrigger value="simple">常规模式</TabsTrigger>
            <TabsTrigger value="advanced">高级模式</TabsTrigger>
          </TabsList>
          <span className="rounded-md bg-primary/10 px-2 py-1 text-xs font-medium text-primary">预览</span>
        </div>

        <TabsContent value={previewMode} className="mt-0">
          <div className="rounded-xl border border-border bg-card p-5 shadow-sm">
            <div className="mb-5">
              <h3 className="text-base font-semibold">参数填写</h3>
              <p className="mt-0.5 text-xs text-muted-foreground">
                标 <span className="text-destructive">*</span> 为必填项；当前模拟
                {previewMode === "simple" ? "常规（customMode=false）" : "高级（customMode=true）"} 下的可见字段。
              </p>
            </div>

            {visibleFields.length === 0 ? (
              <div className="py-8 text-center text-sm text-muted-foreground">当前模式下无可见字段</div>
            ) : previewMode === "advanced" && grouped.length > 1 ? (
              <div className="space-y-6">
                {grouped.map((group) => (
                  <div key={group.key} className="space-y-4">
                    <p className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">{group.label}</p>
                    {group.fields.map((item, index) => (
                      <PreviewField field={item.field} key={`${item.fieldKey}-${index}`} index={index} />
                    ))}
                  </div>
                ))}
              </div>
            ) : (
              <div className="space-y-5">
                {visibleFields.map((field, index) => (
                  <PreviewField field={field} key={`${field.fieldKey || "field"}-${index}`} index={index} />
                ))}
              </div>
            )}
          </div>
        </TabsContent>
      </Tabs>
      <p className="mt-2 text-xs text-muted-foreground">预览会随左侧配置实时更新，可切换常规/高级查看分层与显隐效果。</p>
    </div>
  )
}

function buildPreviewValues(fields: EditableField[]): Record<string, unknown> {
  const values: Record<string, unknown> = {
    customMode: "false",
    instrumental: false,
    model: "V5_5",
  }
  for (const field of fields) {
    if (field.defaultValue.trim()) {
      if (field.fieldType === "checkbox") values[field.fieldKey] = field.defaultValue === "true"
      else if (field.fieldType === "slider" || field.fieldType === "number") values[field.fieldKey] = Number(field.defaultValue)
      else values[field.fieldKey] = field.defaultValue
    } else if (field.uiMeta.defaultValue !== undefined) {
      values[field.fieldKey] = field.uiMeta.defaultValue
    } else if (field.fieldType === "checkbox") {
      values[field.fieldKey] = false
    }
  }
  return values
}

function PreviewField({ field, index }: { field: EditableField; index: number }) {
  const label = field.fieldName.trim() || `未命名字段 ${index + 1}`
  const placeholder = field.placeholder.trim() || defaultPlaceholder(field)
  const firstValue = field.options[0]?.value || field.options[0]?.label

  return (
    <div className="space-y-2">
      <label className="text-sm font-medium">
        {label}
        {field.required ? <span className="text-destructive"> *</span> : null}
      </label>

      {field.fieldType === "textarea" ? (
        <div className="min-h-24 rounded-md border border-border bg-background px-3 py-2 text-sm text-muted-foreground">
          {placeholder}
        </div>
      ) : supportsOptions(field.fieldType) && field.options.length > 0 ? (
        <div className="flex flex-wrap gap-2">
          {field.options.map((option, optionIndex) => {
            const text = option.label.trim() || option.value.trim() || `选项 ${optionIndex + 1}`
            const selected = (option.value || option.label) === firstValue
            return (
              <button
                key={`${option.value || option.label || "option"}-${optionIndex}`}
                type="button"
                className={
                  selected
                    ? "rounded-md border border-primary bg-primary/10 px-3 py-1.5 text-xs font-medium text-primary"
                    : "rounded-md border border-border bg-background px-3 py-1.5 text-xs font-medium text-foreground/70"
                }
              >
                {text}
              </button>
            )
          })}
        </div>
      ) : field.fieldType === "checkbox" ? (
        <div className="flex min-h-10 items-center gap-2 rounded-md border border-border bg-background px-3 py-2 text-sm">
          <span className="h-4 w-4 rounded border border-border bg-background" />
          <span>{field.placeholder.trim() || label}</span>
        </div>
      ) : field.fieldType === "image" || field.fieldType === "multi_image" || field.fieldType === "file" ? (
        <div className="flex h-10 items-center gap-2 rounded-md border border-border bg-background px-3 py-2 text-sm text-muted-foreground">
          {field.fieldType === "file" ? <FileUp className="h-4 w-4" /> : <ImageUp className="h-4 w-4" />}
          <span className="truncate">{placeholder}</span>
        </div>
      ) : field.fieldType === "slider" ? (
        <div className="space-y-2">
          <div className="h-2 rounded-full bg-muted">
            <div className="h-2 w-2/3 rounded-full bg-primary" />
          </div>
          <p className="text-xs text-muted-foreground">{field.defaultValue.trim() || field.uiMeta.defaultValue || "0.65"}</p>
        </div>
      ) : (
        <div className="flex h-10 items-center rounded-md border border-border bg-background px-3 py-2 text-sm text-muted-foreground">
          {placeholder}
        </div>
      )}

      {field.placeholder ? <p className="text-[11px] text-muted-foreground">{field.placeholder}</p> : null}
      <div className="flex flex-wrap gap-1.5">
        {field.executionRequired ? (
          <span className="rounded bg-amber-500/10 px-1.5 py-0.5 text-[10px] font-medium text-amber-700 dark:text-amber-300">
            执行必填
          </span>
        ) : null}
        {field.userRequired ? (
          <span className="rounded bg-destructive/10 px-1.5 py-0.5 text-[10px] font-medium text-destructive">
            缺失追问
          </span>
        ) : (
          <span className="rounded bg-emerald-500/10 px-1.5 py-0.5 text-[10px] font-medium text-emerald-700 dark:text-emerald-300">
            可自动填充
          </span>
        )}
        {field.defaultValue ? (
          <span className="rounded bg-primary/10 px-1.5 py-0.5 text-[10px] font-medium text-primary">
            默认 {field.defaultValue}
          </span>
        ) : null}
      </div>
    </div>
  )
}

function defaultPlaceholder(field: EditableField) {
  if (field.fieldType === "number" || field.fieldType === "slider") return "请输入数字"
  if (field.fieldType === "multi_image") return "选择多张参考图"
  if (field.fieldType === "image" || field.fieldType === "file") return "请输入资源 URL"
  return `请输入${field.fieldName.trim() || "内容"}`
}
