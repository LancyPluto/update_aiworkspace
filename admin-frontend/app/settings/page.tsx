"use client"

import { useEffect, useState } from "react"
import { AdminLayout } from "@/components/admin/admin-layout"
import { AgentModelSettings } from "@/components/admin/agent-model-settings"
import { EngineApiSettings } from "@/components/admin/engine-api-settings"
import { AdminHeader } from "@/components/admin/header"
import { Button } from "@/components/ui/button"
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Switch } from "@/components/ui/switch"
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs"
import { Textarea } from "@/components/ui/textarea"
import { ApiError } from "@/lib/api/http"
import { downloadConfigBundle, exportConfigBundle, importConfigBundle, readConfigBundleFile } from "@/lib/api/config-bundles"
import { fetchSettings, updateSettings } from "@/lib/api/settings"
import { CheckCircle, Database, Download, KeyRound, RefreshCw, Save, Server, Settings2, Shield, Upload } from "lucide-react"

interface SettingsForm {
  platformName: string
  platformDescription: string
  signupGrant: string
  taskMaxRetry: string
  queueEnabled: boolean
  creditDeductEnabled: boolean
  maintenanceMode: boolean
  jwtHours: string
  allowedCors: string
}

const defaults: SettingsForm = {
  platformName: "AI 工具超市",
  platformDescription: "一站式 AI 运营助手，帮助企业提升内容生产与运营效率。",
  signupGrant: "100",
  taskMaxRetry: "3",
  queueEnabled: true,
  creditDeductEnabled: true,
  maintenanceMode: false,
  jwtHours: "24",
  allowedCors: "http://127.0.0.1:5173\nhttp://127.0.0.1:5174",
}

function boolToString(value: boolean) {
  return value ? "true" : "false"
}

function stringToBool(value: string | undefined, fallback: boolean) {
  if (value == null || value === "") return fallback
  return value === "true"
}

export default function SettingsPage() {
  const [form, setForm] = useState<SettingsForm>(defaults)
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [importing, setImporting] = useState(false)
  const [saved, setSaved] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [modelRefreshKey, setModelRefreshKey] = useState(0)
  const [exportDialogOpen, setExportDialogOpen] = useState(false)
  const [exporting, setExporting] = useState(false)

  async function loadSettings() {
    setLoading(true)
    setError(null)
    try {
      const data = await fetchSettings()
      setForm({
        platformName: data["platform.name"] ?? defaults.platformName,
        platformDescription: data["platform.description"] ?? defaults.platformDescription,
        signupGrant: data["credits.signupGrant"] ?? defaults.signupGrant,
        taskMaxRetry: data["tasks.maxRetry"] ?? defaults.taskMaxRetry,
        queueEnabled: stringToBool(data["tasks.queueEnabled"], defaults.queueEnabled),
        creditDeductEnabled: stringToBool(data["credits.deductEnabled"], defaults.creditDeductEnabled),
        maintenanceMode: stringToBool(data["system.maintenanceMode"], defaults.maintenanceMode),
        jwtHours: data["security.jwtHours"] ?? defaults.jwtHours,
        allowedCors: data["security.allowedCors"] ?? defaults.allowedCors,
      })
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "加载系统配置失败")
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    loadSettings()
  }, [])

  function updateForm<K extends keyof SettingsForm>(key: K, value: SettingsForm[K]) {
    setForm((current) => ({ ...current, [key]: value }))
  }

  async function saveSettings() {
    setSaving(true)
    setSaved(false)
    setError(null)
    setNotice(null)
    try {
      await updateSettings({
        "platform.name": form.platformName,
        "platform.description": form.platformDescription,
        "credits.signupGrant": form.signupGrant,
        "tasks.maxRetry": form.taskMaxRetry,
        "tasks.queueEnabled": boolToString(form.queueEnabled),
        "credits.deductEnabled": boolToString(form.creditDeductEnabled),
        "system.maintenanceMode": boolToString(form.maintenanceMode),
        "security.jwtHours": form.jwtHours,
        "security.allowedCors": form.allowedCors,
      })
      setSaved(true)
      setTimeout(() => setSaved(false), 1800)
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "保存系统配置失败")
    } finally {
      setSaving(false)
    }
  }

  async function handleExportBundle(includeSecrets: boolean) {
    setExporting(true)
    setError(null)
    setNotice(null)
    try {
      const bundle = await exportConfigBundle(includeSecrets)
      downloadConfigBundle(bundle)
      setExportDialogOpen(false)
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "导出配置包失败")
    } finally {
      setExporting(false)
    }
  }

  async function handleImportBundle(file: File | undefined) {
    if (!file) return
    setImporting(true)
    setError(null)
    setNotice(null)
    try {
      const bundle = await readConfigBundleFile(file)
      const result = await importConfigBundle(bundle)
      await loadSettings()
      setModelRefreshKey((key) => key + 1)
      setSaved(true)
      const warningText = result.warnings?.length ? `，提示：${result.warnings.join("；")}` : ""
      setNotice(`导入完成：模型 ${result.modelConfigs}、分类 ${result.categories}、工具 ${result.tools}、字段 ${result.fields}${warningText}`)
      setTimeout(() => setSaved(false), 1800)
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "导入配置包失败，请确认 JSON 格式正确")
    } finally {
      setImporting(false)
    }
  }

  const description = error
    ? `配置加载异常：${error}`
    : notice
      ? notice
    : loading
      ? "正在从数据库加载系统配置"
      : "系统配置会保存到后端 system_settings 表"

  return (
    <AdminLayout>
      <AdminHeader title="系统配置" description={description} />

      <div className="p-6">
        <Tabs defaultValue="model" className="space-y-6">
          <TabsList className="flex h-auto flex-wrap justify-start gap-2 bg-transparent p-0">
            <TabsTrigger value="model" className="gap-2">
              <Settings2 className="h-4 w-4" />
              大模型接入
            </TabsTrigger>
            <TabsTrigger value="engine-api" className="gap-2">
              <KeyRound className="h-4 w-4" />
              引擎 API
            </TabsTrigger>
            <TabsTrigger value="system" className="gap-2">
              <Server className="h-4 w-4" />
              基础设置
            </TabsTrigger>
            <TabsTrigger value="features" className="gap-2">
              <Database className="h-4 w-4" />
              功能开关
            </TabsTrigger>
            <TabsTrigger value="security" className="gap-2">
              <Shield className="h-4 w-4" />
              安全设置
            </TabsTrigger>
          </TabsList>

          <TabsContent value="model">
            <AgentModelSettings refreshKey={modelRefreshKey} />
          </TabsContent>

          <TabsContent value="engine-api">
            <EngineApiSettings refreshKey={modelRefreshKey} />
          </TabsContent>

          <TabsContent value="system" className="space-y-5">
            <section className="rounded-lg border border-border bg-card p-5">
              <div className="grid gap-5 md:grid-cols-2">
                <div className="space-y-2">
                  <Label>平台名称</Label>
                  <Input value={form.platformName} onChange={(event) => updateForm("platformName", event.target.value)} />
                </div>
                <div className="space-y-2">
                  <Label>新用户赠送算力</Label>
                  <Input type="number" value={form.signupGrant} onChange={(event) => updateForm("signupGrant", event.target.value)} />
                </div>
              </div>
              <div className="mt-5 space-y-2">
                <Label>平台描述</Label>
                <Textarea value={form.platformDescription} onChange={(event) => updateForm("platformDescription", event.target.value)} />
              </div>
            </section>
          </TabsContent>

          <TabsContent value="features" className="space-y-5">
            <section className="rounded-lg border border-border bg-card p-5">
              <div className="grid gap-5 md:grid-cols-2">
                <div className="space-y-2">
                  <Label>任务最大重试次数</Label>
                  <Input type="number" value={form.taskMaxRetry} onChange={(event) => updateForm("taskMaxRetry", event.target.value)} />
                </div>
              </div>
              <div className="mt-5 space-y-3">
                {[
                  { key: "queueEnabled" as const, label: "启用任务队列", desc: "使用 Redis 队列异步执行任务。" },
                  { key: "creditDeductEnabled" as const, label: "启用算力扣减", desc: "任务完成后自动扣减用户算力。" },
                  { key: "maintenanceMode" as const, label: "维护模式", desc: "开启后可临时关闭用户侧核心功能。" },
                ].map((item) => (
                  <div key={item.key} className="flex items-center justify-between rounded-md bg-secondary p-4">
                    <div>
                      <p className="font-medium">{item.label}</p>
                      <p className="text-sm text-muted-foreground">{item.desc}</p>
                    </div>
                    <Switch checked={form[item.key]} onCheckedChange={(value) => updateForm(item.key, value)} />
                  </div>
                ))}
              </div>
            </section>
          </TabsContent>

          <TabsContent value="security" className="space-y-5">
            <section className="rounded-lg border border-border bg-card p-5">
              <div className="grid gap-5 md:grid-cols-2">
                <div className="space-y-2">
                  <Label>JWT 过期时间（小时）</Label>
                  <Input type="number" value={form.jwtHours} onChange={(event) => updateForm("jwtHours", event.target.value)} />
                </div>
              </div>
              <div className="mt-5 space-y-2">
                <Label>允许的 CORS 来源</Label>
                <Textarea className="font-mono" value={form.allowedCors} onChange={(event) => updateForm("allowedCors", event.target.value)} />
              </div>
            </section>
          </TabsContent>
        </Tabs>

        <div className="mt-6 flex items-center justify-end gap-3">
          <Button variant="outline" className="gap-2" onClick={() => setExportDialogOpen(true)} disabled={loading || saving || importing || exporting}>
            <Download className="h-4 w-4" />
            导出配置包
          </Button>
          <Button variant="outline" className="relative gap-2" disabled={loading || saving || importing}>
            <Upload className={importing ? "h-4 w-4 animate-spin" : "h-4 w-4"} />
            {importing ? "导入中..." : "导入配置包"}
            <input
              type="file"
              accept="application/json,.json"
              className="absolute inset-0 cursor-pointer opacity-0"
              disabled={loading || saving || importing}
              onChange={(event) => {
                handleImportBundle(event.target.files?.[0])
                event.currentTarget.value = ""
              }}
            />
          </Button>
          <Button variant="outline" className="gap-2" onClick={loadSettings} disabled={loading || saving || importing}>
            <RefreshCw className={loading ? "h-4 w-4 animate-spin" : "h-4 w-4"} />
            刷新
          </Button>
          <Button className="min-w-32 gap-2" onClick={saveSettings} disabled={saving || importing}>
            {saved ? <CheckCircle className="h-4 w-4" /> : <Save className={saving ? "h-4 w-4 animate-spin" : "h-4 w-4"} />}
            {saved ? "已保存" : saving ? "保存中..." : "保存配置"}
          </Button>
        </div>

        <Dialog open={exportDialogOpen} onOpenChange={setExportDialogOpen}>
          <DialogContent>
            <DialogHeader>
              <DialogTitle>导出配置包</DialogTitle>
              <DialogDescription>
                请选择是否把模型 API Key 和额外鉴权信息一起写入 JSON。含密钥文件只适合可信成员之间临时流转。
              </DialogDescription>
            </DialogHeader>

            <div className="grid gap-3 sm:grid-cols-2">
              <button
                type="button"
                className="rounded-lg border border-border bg-card p-4 text-left transition-colors hover:bg-accent disabled:cursor-not-allowed disabled:opacity-60"
                disabled={exporting}
                onClick={() => handleExportBundle(false)}
              >
                <div className="flex items-center gap-2 font-medium">
                  <Shield className="h-4 w-4 text-primary" />
                  不含密钥
                </div>
                <p className="mt-2 text-sm leading-6 text-muted-foreground">适合提交到分支、分享给成员或作为默认配置模板。</p>
              </button>

              <button
                type="button"
                className="rounded-lg border border-amber-500/40 bg-amber-500/10 p-4 text-left transition-colors hover:bg-amber-500/15 disabled:cursor-not-allowed disabled:opacity-60"
                disabled={exporting}
                onClick={() => handleExportBundle(true)}
              >
                <div className="flex items-center gap-2 font-medium">
                  <KeyRound className="h-4 w-4 text-amber-500" />
                  包含密钥
                </div>
                <p className="mt-2 text-sm leading-6 text-muted-foreground">仅用于可信开发环境快速同步，导出后请不要提交仓库。</p>
              </button>
            </div>

            <DialogFooter>
              <Button variant="outline" onClick={() => setExportDialogOpen(false)} disabled={exporting}>
                取消
              </Button>
            </DialogFooter>
          </DialogContent>
        </Dialog>
      </div>
    </AdminLayout>
  )
}
