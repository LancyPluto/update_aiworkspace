"use client"

import { useEffect, useState } from "react"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Switch } from "@/components/ui/switch"
import { Textarea } from "@/components/ui/textarea"
import { ApiError } from "@/lib/api/http"
import { fetchSettingVersions, fetchSettings, restoreAgentDefaults, restoreDefaultSetting, updateSettings, type SettingsMap } from "@/lib/api/settings"
import { CheckCircle2, History, RefreshCw, RotateCcw, Save } from "lucide-react"

const defaults: SettingsMap = {
  "agent.system_prompt": "",
  "agent.deep_agents_system_prompt": "",
  "agent.router.enabled": "true",
  "agent.router.prompt": "",
  "agent.router.min_confidence": "0.7",
  "agent.router.fallback_to_rules": "true",
  "agent.memory.auto_save_enabled": "true",
  "agent.memory.retrieval_limit": "6",
  "agent.memory.enabled_types": "user_profile,workspace_fact,preference,tool_lesson,workflow_recipe,custom",
  "agent.memory.write_prompt": "",
  "agent.memory.retrieval_prompt": "",
  "agent.memory.tool_loop_enabled": "true",
  "agent.memory.consolidation_enabled": "true",
  "agent.memory.consolidation_llm_enabled": "true",
  "agent.memory.consolidation_turn_interval": "8",
  "agent.memory.consolidation_char_threshold": "4000",
  "agent.memory.consolidation_token_threshold": "3000",
  "agent.memory.consolidation_recent_tool_threshold": "3",
  "agent.memory.consolidation_max_context_messages": "24",
  "agent.memory.consolidation_prompt": "",
  "agent.memory.consolidation_min_confidence": "0.72",
  "agent.memory.candidate_confidence_threshold": "0.55",
  "agent.runtime.max_model_calls": "5",
  "agent.runtime.max_tool_calls": "3",
  "agent.runtime.max_history_messages": "20",
  "agent.runtime.tool_execution_timeout_seconds": "120",
  "agent.runtime.image_tool_execution_timeout_seconds": "600",
  "agent.runtime.video_tool_execution_timeout_seconds": "900",
  "agent.runtime.music_tool_execution_timeout_seconds": "900",
  "agent.runtime.tool_poll_interval_seconds": "1",
  "agent.runtime.tool_stream_relay_enabled": "true",
  "agent.runtime.product_tool_loop_enabled": "true",
  "agent.runtime.product_tool_loop_max_calls": "1",
  "agent.runtime.product_tool_loop_fallback_to_router": "true",
  "agent.audit.payload_retention_days": "30",
}

const textKeys = [
  "agent.system_prompt",
  "agent.deep_agents_system_prompt",
  "agent.router.prompt",
  "agent.memory.write_prompt",
  "agent.memory.retrieval_prompt",
  "agent.memory.consolidation_prompt",
]

const boolKeys = [
  "agent.router.enabled",
  "agent.router.fallback_to_rules",
  "agent.memory.auto_save_enabled",
  "agent.memory.tool_loop_enabled",
  "agent.memory.consolidation_enabled",
  "agent.memory.consolidation_llm_enabled",
  "agent.runtime.tool_stream_relay_enabled",
  "agent.runtime.product_tool_loop_enabled",
  "agent.runtime.product_tool_loop_fallback_to_router",
]

const labels: Record<string, string> = {
  "agent.system_prompt": "基础系统提示词",
  "agent.deep_agents_system_prompt": "工作区 Agent 系统提示词",
  "agent.router.enabled": "启用 LLM 路由",
  "agent.router.prompt": "路由提示词",
  "agent.router.min_confidence": "路由最低置信度",
  "agent.router.fallback_to_rules": "路由失败回退规则",
  "agent.memory.auto_save_enabled": "自动保存记忆",
  "agent.memory.retrieval_limit": "每轮检索记忆数",
  "agent.memory.enabled_types": "启用记忆类型",
  "agent.memory.write_prompt": "记忆写入提示词",
  "agent.memory.retrieval_prompt": "记忆注入提示词",
  "agent.memory.tool_loop_enabled": "启用记忆工具循环",
  "agent.memory.consolidation_enabled": "启用记忆合并",
  "agent.memory.consolidation_llm_enabled": "启用 LLM 自动梳理",
  "agent.memory.consolidation_turn_interval": "合并轮次阈值",
  "agent.memory.consolidation_char_threshold": "合并字符阈值",
  "agent.memory.consolidation_token_threshold": "合并上下文 Token 阈值",
  "agent.memory.consolidation_recent_tool_threshold": "成功工具次数阈值",
  "agent.memory.consolidation_max_context_messages": "梳理最大上下文消息数",
  "agent.memory.consolidation_prompt": "自动梳理提示词",
  "agent.memory.consolidation_min_confidence": "直接保存置信度",
  "agent.memory.candidate_confidence_threshold": "候选记忆置信度",
  "agent.runtime.max_model_calls": "最大模型调用次数",
  "agent.runtime.max_tool_calls": "最大工具调用次数",
  "agent.runtime.max_history_messages": "最大历史消息数",
  "agent.runtime.tool_execution_timeout_seconds": "普通工具超时秒数",
  "agent.runtime.image_tool_execution_timeout_seconds": "图片工具超时秒数",
  "agent.runtime.video_tool_execution_timeout_seconds": "视频工具超时秒数",
  "agent.runtime.music_tool_execution_timeout_seconds": "音乐工具超时秒数",
  "agent.runtime.tool_poll_interval_seconds": "工具轮询间隔秒数",
  "agent.runtime.tool_stream_relay_enabled": "转发工具流式进度",
  "agent.runtime.product_tool_loop_enabled": "启用产品工具循环",
  "agent.runtime.product_tool_loop_max_calls": "产品工具循环最大调用",
  "agent.runtime.product_tool_loop_fallback_to_router": "产品工具拒绝后回退路由",
  "agent.audit.payload_retention_days": "Agent 稽查正文保留天数",
}

const numberConstraints: Record<string, { min: number; max: number; step?: number }> = {
  "agent.router.min_confidence": { min: 0, max: 1, step: 0.01 },
  "agent.memory.retrieval_limit": { min: 0, max: 20 },
  "agent.memory.consolidation_turn_interval": { min: 1, max: 200 },
  "agent.memory.consolidation_char_threshold": { min: 200, max: 50000 },
  "agent.memory.consolidation_token_threshold": { min: 0, max: 200000 },
  "agent.memory.consolidation_recent_tool_threshold": { min: 0, max: 50 },
  "agent.memory.consolidation_max_context_messages": { min: 4, max: 100 },
  "agent.memory.consolidation_min_confidence": { min: 0, max: 1, step: 0.01 },
  "agent.memory.candidate_confidence_threshold": { min: 0, max: 1, step: 0.01 },
  "agent.runtime.max_model_calls": { min: 1, max: 50 },
  "agent.runtime.max_tool_calls": { min: 1, max: 50 },
  "agent.runtime.max_history_messages": { min: 1, max: 100 },
  "agent.runtime.tool_execution_timeout_seconds": { min: 10, max: 3600 },
  "agent.runtime.image_tool_execution_timeout_seconds": { min: 10, max: 3600 },
  "agent.runtime.video_tool_execution_timeout_seconds": { min: 10, max: 7200 },
  "agent.runtime.music_tool_execution_timeout_seconds": { min: 10, max: 7200 },
  "agent.runtime.tool_poll_interval_seconds": { min: 0.2, max: 30, step: 0.1 },
  "agent.runtime.product_tool_loop_max_calls": { min: 1, max: 20 },
  "agent.audit.payload_retention_days": { min: 1, max: 365 },
}

function asBool(value?: string) {
  return value === "true"
}

function clampNumber(key: string, value?: string) {
  const constraint = numberConstraints[key]
  if (!constraint) return value ?? ""
  const parsed = Number(value)
  const fallback = Number(defaults[key])
  const next = Number.isFinite(parsed) ? parsed : fallback
  return String(Math.min(constraint.max, Math.max(constraint.min, next)))
}

export function AgentRuntimeSettings() {
  const [form, setForm] = useState<SettingsMap>(defaults)
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [saved, setSaved] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [versionNote, setVersionNote] = useState<string | null>(null)

  async function load() {
    setLoading(true)
    setError(null)
    try {
      const data = await fetchSettings()
      setForm({ ...defaults, ...Object.fromEntries(Object.keys(defaults).map((key) => [key, data[key] ?? defaults[key]])) })
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "加载 Agent 参数失败")
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    void load()
  }, [])

  function setValue(key: string, value: string) {
    setForm((current) => ({ ...current, [key]: value }))
  }

  async function save() {
    setSaving(true)
    setError(null)
    try {
      const normalized = Object.fromEntries(
        Object.keys(defaults).map((key) => [key, numberConstraints[key] ? clampNumber(key, form[key]) : form[key] ?? ""]),
      )
      await updateSettings(normalized)
      setForm((current) => ({ ...current, ...normalized }))
      setSaved(true)
      setTimeout(() => setSaved(false), 1600)
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "保存 Agent 参数失败")
    } finally {
      setSaving(false)
    }
  }

  async function restore() {
    setSaving(true)
    setError(null)
    try {
      const data = await restoreAgentDefaults()
      setForm({ ...defaults, ...Object.fromEntries(Object.keys(defaults).map((key) => [key, data[key] ?? defaults[key]])) })
      setSaved(true)
      setTimeout(() => setSaved(false), 1600)
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "恢复默认失败")
    } finally {
      setSaving(false)
    }
  }

  async function restoreSection(keys: string[]) {
    setSaving(true)
    setError(null)
    try {
      const restored = await Promise.all(keys.map((key) => restoreDefaultSetting(key)))
      const data = Object.assign({}, ...restored)
      setForm((current) => {
        const next = { ...current }
        keys.forEach((key) => {
          next[key] = data[key] ?? defaults[key]
        })
        return next
      })
      setSaved(true)
      setTimeout(() => setSaved(false), 1600)
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "恢复默认失败")
    } finally {
      setSaving(false)
    }
  }

  async function showVersions(key: string) {
    try {
      const versions = await fetchSettingVersions(key)
      setVersionNote(versions.length ? `${labels[key]}：最近 ${versions.length} 个版本，最新 ${versions[0].createdAt || ""}` : `${labels[key]}：暂无版本记录`)
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "读取版本记录失败")
    }
  }

  const numberKeys = Object.keys(defaults).filter((key) => !textKeys.includes(key) && !boolKeys.includes(key))

  return (
    <div className="space-y-5">
      {error ? <p className="rounded-md border border-destructive/30 bg-destructive/10 p-3 text-sm text-destructive">{error}</p> : null}
      {versionNote ? <p className="rounded-md border bg-secondary p-3 text-sm text-muted-foreground">{versionNote}</p> : null}

      <Card>
        <CardHeader>
          <div className="flex items-start justify-between gap-3">
            <div>
              <CardTitle>基础提示词</CardTitle>
              <CardDescription>影响新发起的 Agent run，正在运行的任务不受影响。</CardDescription>
            </div>
            <Button variant="outline" size="sm" disabled={saving} onClick={() => restoreSection(textKeys)}>恢复本区</Button>
          </div>
        </CardHeader>
        <CardContent className="space-y-4">
          {textKeys.map((key) => (
            <div key={key} className="space-y-2">
              <div className="flex items-center justify-between gap-2">
                <Label>{labels[key]}</Label>
                <Button type="button" variant="ghost" size="sm" className="gap-2" onClick={() => showVersions(key)}>
                  <History className="h-4 w-4" />
                  版本
                </Button>
              </div>
              <Textarea className="min-h-28 font-mono text-xs" value={form[key] || ""} onChange={(event) => setValue(key, event.target.value)} />
            </div>
          ))}
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <div className="flex items-start justify-between gap-3">
            <div>
              <CardTitle>策略与限制</CardTitle>
              <CardDescription>路由、记忆和运行预算会在下一轮上下文中下发给 Agent 服务；过小的调用次数或超时会让 Agent 更容易提前停止。</CardDescription>
            </div>
            <Button variant="outline" size="sm" disabled={saving} onClick={() => restoreSection([...boolKeys, ...numberKeys])}>恢复本区</Button>
          </div>
        </CardHeader>
        <CardContent className="space-y-5">
          <div className="grid gap-4 md:grid-cols-2">
            {boolKeys.map((key) => (
              <div key={key} className="flex items-center justify-between rounded-md bg-secondary p-4">
                <Label>{labels[key]}</Label>
                <Switch checked={asBool(form[key])} onCheckedChange={(value) => setValue(key, value ? "true" : "false")} />
              </div>
            ))}
          </div>
          <div className="grid gap-4 md:grid-cols-3">
            {numberKeys.map((key) => (
              <div key={key} className="space-y-2">
                <Label>{labels[key]}</Label>
                <Input
                  type="number"
                  min={numberConstraints[key]?.min}
                  max={numberConstraints[key]?.max}
                  step={numberConstraints[key]?.step}
                  value={form[key] || ""}
                  onChange={(event) => setValue(key, event.target.value)}
                  onBlur={() => setValue(key, clampNumber(key, form[key]))}
                />
              </div>
            ))}
          </div>
        </CardContent>
      </Card>

      <div className="flex justify-end gap-3">
        <Button variant="outline" className="gap-2" disabled={loading || saving} onClick={load}>
          <RefreshCw className={loading ? "h-4 w-4 animate-spin" : "h-4 w-4"} />
          刷新
        </Button>
        <Button variant="outline" className="gap-2" disabled={saving} onClick={restore}>
          <RotateCcw className="h-4 w-4" />
          恢复默认
        </Button>
        <Button className="gap-2" disabled={saving} onClick={save}>
          {saved ? <CheckCircle2 className="h-4 w-4" /> : <Save className={saving ? "h-4 w-4 animate-spin" : "h-4 w-4"} />}
          {saved ? "已保存" : "保存 Agent 参数"}
        </Button>
      </div>
    </div>
  )
}
