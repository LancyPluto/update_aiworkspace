"use client"

import { Plus, Trash2 } from "lucide-react"
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

export function FieldSchemaEditor({ fields, onChange, disabled }: FieldSchemaEditorProps) {
  function updateField(index: number, patch: Partial<EditableField>) {
    const next = fields.map((field, i) => {
      if (i !== index) return field
      const merged = { ...field, ...patch }
      if (patch.fieldType && !supportsOptions(patch.fieldType)) {
        merged.options = []
      }
      return merged
    })
    onChange(next)
  }

  function updateOption(fieldIndex: number, optionIndex: number, patch: Partial<{ label: string; value: string }>) {
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
                    onCheckedChange={(checked) => updateField(index, { required: checked })}
                  />
                  <Label className="text-sm">必填</Label>
                </div>
              </div>
            </div>

            <div className="space-y-1.5">
              <Label>占位提示 placeholder</Label>
              <Input
                value={field.placeholder}
                disabled={disabled}
                placeholder="用户未填写时看到的说明"
                onChange={(e) => updateField(index, { placeholder: e.target.value })}
              />
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
                  用户端将显示为{field.fieldType === "radio" ? "单选按钮" : "下拉框"}，只能在这些值中选择，无需手输比例。
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
                      </div>
                    ))}
                  </div>
                )}
              </div>
            ) : null}
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
