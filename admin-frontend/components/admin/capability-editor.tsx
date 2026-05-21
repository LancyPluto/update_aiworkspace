"use client"

import { Plus, Trash2 } from "lucide-react"
import { Button } from "@/components/ui/button"
import { Checkbox } from "@/components/ui/checkbox"
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
  ASPECT_RATIO_OPTIONS,
  CAPABILITY_TYPE_OPTIONS,
  CODE_LANGUAGE_OPTIONS,
  FILE_TYPE_OPTIONS,
  capabilityTypeLabel,
  createEmptyCapability,
  defaultCapabilityConfig,
  type Capability,
  type CapabilityType,
} from "@/lib/ai-tool-types"

interface CapabilityEditorProps {
  capabilities: Capability[]
  onChange: (capabilities: Capability[]) => void
}

function updateCapability(
  capabilities: Capability[],
  index: number,
  patch: Partial<Capability> | ((current: Capability) => Capability),
): Capability[] {
  return capabilities.map((item, i) => {
    if (i !== index) return item
    if (typeof patch === "function") return patch(item)
    return { ...item, ...patch }
  })
}

function ConfigFields({
  capability,
  onChange,
}: {
  capability: Capability
  onChange: (next: Capability) => void
}) {
  const config = capability.config

  function setConfig(key: string, value: unknown) {
    onChange({ ...capability, config: { ...config, [key]: value } })
  }

  function toggleStringArray(key: string, value: string, checked: boolean) {
    const current = Array.isArray(config[key]) ? (config[key] as string[]) : []
    const next = checked ? [...current, value] : current.filter((item) => item !== value)
    setConfig(key, next)
  }

  switch (capability.type) {
    case "imageGeneration": {
      const aspectRatios = Array.isArray(config.aspectRatios) ? (config.aspectRatios as string[]) : []
      const defaultRatio = typeof config.defaultRatio === "string" ? config.defaultRatio : "1:1"
      return (
        <div className="space-y-3">
          <div>
            <Label className="text-xs">支持比例</Label>
            <div className="mt-2 flex flex-wrap gap-2">
              {ASPECT_RATIO_OPTIONS.map((ratio) => (
                <label key={ratio} className="flex items-center gap-1.5 text-xs">
                  <Checkbox
                    checked={aspectRatios.includes(ratio)}
                    onCheckedChange={(checked) => toggleStringArray("aspectRatios", ratio, checked === true)}
                  />
                  {ratio}
                </label>
              ))}
            </div>
          </div>
          <div className="grid gap-3 sm:grid-cols-2">
            <div>
              <Label className="text-xs">默认比例</Label>
              <Select value={defaultRatio} onValueChange={(value) => setConfig("defaultRatio", value)}>
                <SelectTrigger className="mt-1.5">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {(aspectRatios.length > 0 ? aspectRatios : ASPECT_RATIO_OPTIONS).map((ratio) => (
                    <SelectItem key={ratio} value={ratio}>
                      {ratio}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
            <div>
              <Label className="text-xs">单次最大生成数</Label>
              <Input
                className="mt-1.5"
                type="number"
                min={1}
                max={4}
                value={typeof config.maxImagesPerRequest === "number" ? config.maxImagesPerRequest : 1}
                onChange={(e) => setConfig("maxImagesPerRequest", Number(e.target.value) || 1)}
              />
            </div>
          </div>
        </div>
      )
    }
    case "fileReading": {
      const supportedFileTypes = Array.isArray(config.supportedFileTypes)
        ? (config.supportedFileTypes as string[])
        : []
      return (
        <div className="space-y-3">
          <div>
            <Label className="text-xs">支持文件类型</Label>
            <div className="mt-2 flex flex-wrap gap-2">
              {FILE_TYPE_OPTIONS.map((ext) => (
                <label key={ext} className="flex items-center gap-1.5 text-xs">
                  <Checkbox
                    checked={supportedFileTypes.includes(ext)}
                    onCheckedChange={(checked) => toggleStringArray("supportedFileTypes", ext, checked === true)}
                  />
                  .{ext}
                </label>
              ))}
            </div>
          </div>
          <div>
            <Label className="text-xs">单文件最大体积 (MB)</Label>
            <Input
              className="mt-1.5"
              type="number"
              min={1}
              value={typeof config.maxSizeMB === "number" ? config.maxSizeMB : 20}
              onChange={(e) => setConfig("maxSizeMB", Number(e.target.value) || 20)}
            />
          </div>
        </div>
      )
    }
    case "webSearch":
      return (
        <div className="space-y-3">
          <div className="flex items-center justify-between rounded-md border border-border px-3 py-2">
            <Label className="text-xs">显示联网搜索开关</Label>
            <Switch
              checked={config.enabled !== false}
              onCheckedChange={(checked) => setConfig("enabled", checked)}
            />
          </div>
          <div className="flex items-center justify-between rounded-md border border-border px-3 py-2">
            <Label className="text-xs">默认开启</Label>
            <Switch
              checked={config.defaultEnabled === true}
              onCheckedChange={(checked) => setConfig("defaultEnabled", checked)}
            />
          </div>
        </div>
      )
    case "codeExecution": {
      const supportedLanguages = Array.isArray(config.supportedLanguages)
        ? (config.supportedLanguages as string[])
        : []
      return (
        <div>
          <Label className="text-xs">支持语言</Label>
          <div className="mt-2 flex flex-wrap gap-2">
            {CODE_LANGUAGE_OPTIONS.map((lang) => (
              <label key={lang} className="flex items-center gap-1.5 text-xs">
                <Checkbox
                  checked={supportedLanguages.includes(lang)}
                  onCheckedChange={(checked) => toggleStringArray("supportedLanguages", lang, checked === true)}
                />
                {lang}
              </label>
            ))}
          </div>
        </div>
      )
    }
    case "voiceInput":
      return (
        <div>
          <Label className="text-xs">语音识别语言</Label>
          <Input
            className="mt-1.5"
            placeholder="zh-CN"
            value={typeof config.language === "string" ? config.language : "zh-CN"}
            onChange={(e) => setConfig("language", e.target.value)}
          />
        </div>
      )
    default:
      return null
  }
}

export function CapabilityEditor({ capabilities, onChange }: CapabilityEditorProps) {
  function handleTypeChange(index: number, type: CapabilityType) {
    onChange(
      updateCapability(capabilities, index, {
        type,
        config: defaultCapabilityConfig(type),
      }),
    )
  }

  return (
    <div className="space-y-3">
      <div className="flex items-center justify-between">
        <div>
          <p className="text-sm font-medium">能力配置</p>
          <p className="text-xs text-muted-foreground">C 端聊天页将根据能力动态渲染控件</p>
        </div>
        <Button
          type="button"
          variant="outline"
          size="sm"
          onClick={() => onChange([...capabilities, createEmptyCapability()])}
        >
          <Plus className="mr-1 h-3.5 w-3.5" />
          添加能力
        </Button>
      </div>

      {capabilities.length === 0 ? (
        <div className="rounded-lg border border-dashed border-border px-4 py-8 text-center text-sm text-muted-foreground">
          暂未配置能力，可添加图片生成、文件阅读、联网搜索等
        </div>
      ) : (
        capabilities.map((capability, index) => (
          <div key={`${capability.type}-${index}`} className="rounded-lg border border-border bg-secondary/20 p-4">
            <div className="mb-3 flex items-center gap-3">
              <div className="flex-1">
                <Label className="text-xs">能力类型</Label>
                <Select value={capability.type} onValueChange={(value) => handleTypeChange(index, value as CapabilityType)}>
                  <SelectTrigger className="mt-1.5">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    {CAPABILITY_TYPE_OPTIONS.map((option) => (
                      <SelectItem key={option.value} value={option.value}>
                        {option.label}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>
              <Button
                type="button"
                variant="ghost"
                size="icon"
                className="mt-5 shrink-0 text-destructive hover:text-destructive"
                onClick={() => onChange(capabilities.filter((_, i) => i !== index))}
              >
                <Trash2 className="h-4 w-4" />
              </Button>
            </div>
            <p className="mb-3 text-xs text-muted-foreground">{capabilityTypeLabel(capability.type)}</p>
            <ConfigFields
              capability={capability}
              onChange={(next) => onChange(updateCapability(capabilities, index, next))}
            />
          </div>
        ))
      )}
    </div>
  )
}
