"use client"

import { useCallback, useEffect, useState } from "react"
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { ApiError } from "@/lib/api/http"
import {
  ENGINE_API_SETTING_KEYS,
  fetchEngineApiSettings,
  updateEngineApiSettings,
} from "@/lib/api/engine-api-settings"
import { AlertCircle, CheckCircle2, KeyRound, RefreshCw, Save } from "lucide-react"

interface EngineApiForm {
  mineruApiBase: string
  mineruToken: string
  baiduApiKey: string
}

const defaults: EngineApiForm = {
  mineruApiBase: "https://mineru.net",
  mineruToken: "",
  baiduApiKey: "",
}

interface EngineApiSettingsProps {
  refreshKey?: number
}

export function EngineApiSettings({ refreshKey = 0 }: EngineApiSettingsProps) {
  const [form, setForm] = useState<EngineApiForm>(defaults)
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [saved, setSaved] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [maskedToken, setMaskedToken] = useState("")
  const [maskedBaidu, setMaskedBaidu] = useState("")

  const load = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const data = await fetchEngineApiSettings()
      setForm({
        mineruApiBase: data[ENGINE_API_SETTING_KEYS.mineruApiBase] || defaults.mineruApiBase,
        mineruToken: "",
        baiduApiKey: "",
      })
      setMaskedToken(data[ENGINE_API_SETTING_KEYS.mineruToken] || "")
      setMaskedBaidu(data[ENGINE_API_SETTING_KEYS.baiduApiKey] || "")
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "加载引擎 API 配置失败")
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    void load()
  }, [load, refreshKey])

  async function handleSave() {
    setSaving(true)
    setSaved(false)
    setError(null)
    try {
      const payload: Record<string, string> = {
        [ENGINE_API_SETTING_KEYS.mineruApiBase]: form.mineruApiBase.trim(),
      }
      if (form.mineruToken.trim()) {
        payload[ENGINE_API_SETTING_KEYS.mineruToken] = form.mineruToken.trim()
      }
      if (form.baiduApiKey.trim()) {
        payload[ENGINE_API_SETTING_KEYS.baiduApiKey] = form.baiduApiKey.trim()
      }
      const updated = await updateEngineApiSettings(payload)
      setMaskedToken(updated[ENGINE_API_SETTING_KEYS.mineruToken] || "")
      setMaskedBaidu(updated[ENGINE_API_SETTING_KEYS.baiduApiKey] || "")
      setForm((prev) => ({ ...prev, mineruToken: "", baiduApiKey: "" }))
      setSaved(true)
      setTimeout(() => setSaved(false), 1800)
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "保存引擎 API 配置失败")
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="space-y-6">
      <Alert>
        <KeyRound className="h-4 w-4" />
        <AlertTitle>引擎 API（全局）</AlertTitle>
        <AlertDescription>
          MinerU、百度 OCR 等 PPT 引擎依赖的第三方服务在此统一维护。工作台类工具在「工具管理」中可选择「使用系统配置」，无需每个工具重复填写。
        </AlertDescription>
      </Alert>

      {error ? (
        <Alert variant="destructive">
          <AlertCircle className="h-4 w-4" />
          <AlertDescription>{error}</AlertDescription>
        </Alert>
      ) : null}

      <section className="rounded-lg border border-border bg-card p-5 space-y-5">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div>
            <h3 className="text-base font-semibold">MinerU</h3>
            <p className="text-sm text-muted-foreground">PDF / Office 版式解析</p>
          </div>
          <Button type="button" variant="outline" size="sm" className="gap-1" onClick={() => void load()} disabled={loading}>
            <RefreshCw className={`h-3.5 w-3.5 ${loading ? "animate-spin" : ""}`} />
            刷新
          </Button>
        </div>

        {loading ? (
          <p className="text-sm text-muted-foreground">加载中…</p>
        ) : (
          <>
            <div className="space-y-2">
              <Label>服务地址</Label>
              <Input
                value={form.mineruApiBase}
                placeholder="https://mineru.net"
                onChange={(e) => setForm((p) => ({ ...p, mineruApiBase: e.target.value }))}
              />
            </div>
            <div className="space-y-2">
              <Label>API Token</Label>
              <Input
                type="password"
                value={form.mineruToken}
                placeholder={maskedToken ? `已配置（${maskedToken}），留空不修改` : "从 MinerU 控制台获取"}
                onChange={(e) => setForm((p) => ({ ...p, mineruToken: e.target.value }))}
                autoComplete="off"
              />
            </div>

            <div className="border-t border-border pt-5 space-y-2">
              <Label>百度通用文字识别 API Key</Label>
              <p className="text-xs text-muted-foreground">可编辑 PPTX 导出、样式与文字区域提取等</p>
              <Input
                type="password"
                value={form.baiduApiKey}
                placeholder={maskedBaidu ? `已配置（${maskedBaidu}），留空不修改` : "百度云 API Key"}
                onChange={(e) => setForm((p) => ({ ...p, baiduApiKey: e.target.value }))}
                autoComplete="off"
              />
            </div>

            <div className="flex flex-wrap gap-2 pt-2">
              <Button type="button" className="gap-2" onClick={() => void handleSave()} disabled={saving}>
                {saved ? <CheckCircle2 className="h-4 w-4" /> : <Save className="h-4 w-4" />}
                {saved ? "已保存" : saving ? "保存中…" : "保存引擎 API"}
              </Button>
            </div>
          </>
        )}
      </section>
    </div>
  )
}
