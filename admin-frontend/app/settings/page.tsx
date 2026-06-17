"use client"

import { useEffect, useState } from "react"
import { AdminLayout } from "@/components/admin/admin-layout"
import { AgentMemoryManagement } from "@/components/admin/agent-memory-management"
import { AgentRuntimeSettings } from "@/components/admin/agent-runtime-settings"
import { UnifiedApiSettings } from "@/components/admin/unified-api-settings"
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
import { getBaseUrl } from "@/lib/api/http"
import { fetchSettings, updateSettings, uploadCustomerServiceQr } from "@/lib/api/settings"
import type { ConfigBundleImportResult } from "@/lib/api/types"
import { cn } from "@/lib/utils"
import { AlertTriangle, Bot, Brain, CheckCircle, Database, Download, Headphones, KeyRound, Loader2, RefreshCw, Save, Server, Settings2, Shield, Upload } from "lucide-react"

interface SettingsForm {
  platformName: string
  platformDescription: string
  signupGrant: string
  taskMaxRetry: string
  queueEnabled: boolean
  creditDeductEnabled: boolean
  maintenanceMode: boolean
  customerServiceEnabled: boolean
  customerServiceTitle: string
  customerServiceDescription: string
  customerServiceQrCodeUrl: string
  jwtHours: string
  allowedCors: string
}

const defaults: SettingsForm = {
  platformName: "科创点AI",
  platformDescription: "一站式 AI 运营助手，帮助企业提升内容生产与运营效率。",
  signupGrant: "100",
  taskMaxRetry: "3",
  queueEnabled: true,
  creditDeductEnabled: true,
  maintenanceMode: false,
  customerServiceEnabled: true,
  customerServiceTitle: "联系客服",
  customerServiceDescription: "扫码添加客服，获取使用支持",
  customerServiceQrCodeUrl: "",
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

function resolveMediaUrl(url?: string) {
  const raw = url?.trim()
  if (!raw) return ""
  if (raw.startsWith("http://") || raw.startsWith("https://") || raw.startsWith("data:")) return raw
  const path = raw.startsWith("/") ? raw : `/${raw}`
  const baseUrl = getBaseUrl().replace(/\/$/, "")
  return baseUrl ? `${baseUrl}${path}` : path
}

export default function SettingsPage() {
  const [form, setForm] = useState<SettingsForm>(defaults)
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [importing, setImporting] = useState(false)
  const [saved, setSaved] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [importResult, setImportResult] = useState<ConfigBundleImportResult | null>(null)
  const [modelRefreshKey, setModelRefreshKey] = useState(0)
  const [exportDialogOpen, setExportDialogOpen] = useState(false)
  const [exporting, setExporting] = useState(false)
  const [qrUploading, setQrUploading] = useState(false)

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
        customerServiceEnabled: stringToBool(data["customerService.enabled"], defaults.customerServiceEnabled),
        customerServiceTitle: data["customerService.title"] ?? defaults.customerServiceTitle,
        customerServiceDescription: data["customerService.description"] ?? defaults.customerServiceDescription,
        customerServiceQrCodeUrl: data["customerService.qrCodeUrl"] ?? defaults.customerServiceQrCodeUrl,
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

  async function handleQrUpload(file?: File | null) {
    if (!file) return
    setQrUploading(true)
    setError(null)
    setNotice(null)
    setImportResult(null)
    try {
      const uploaded = await uploadCustomerServiceQr(file)
      updateForm("customerServiceQrCodeUrl", uploaded.url)
      setNotice("客服二维码已上传")
      setTimeout(() => setNotice(null), 2000)
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "客服二维码上传失败")
    } finally {
      setQrUploading(false)
    }
  }

  async function saveSettings() {
    setSaving(true)
    setSaved(false)
    setError(null)
    setNotice(null)
    setImportResult(null)
    try {
      await updateSettings({
        "platform.name": form.platformName,
        "platform.description": form.platformDescription,
        "credits.signupGrant": form.signupGrant,
        "tasks.maxRetry": form.taskMaxRetry,
        "tasks.queueEnabled": boolToString(form.queueEnabled),
        "credits.deductEnabled": boolToString(form.creditDeductEnabled),
        "system.maintenanceMode": boolToString(form.maintenanceMode),
        "customerService.enabled": boolToString(form.customerServiceEnabled),
        "customerService.title": form.customerServiceTitle,
        "customerService.description": form.customerServiceDescription,
        "customerService.qrCodeUrl": form.customerServiceQrCodeUrl,
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
      setImportResult(result)
      setNotice(`导入完成：模型 ${result.modelConfigs}、工具 ${result.tools}、字段 ${result.fields}`)
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
        {importResult ? (
          <section className="mb-6 rounded-lg border border-border bg-card p-4">
            <div className="flex flex-wrap items-start justify-between gap-3">
              <div>
                <div className="flex items-center gap-2 text-sm font-medium">
                  {importResult.warnings?.length ? (
                    <AlertTriangle className="h-4 w-4 text-amber-500" />
                  ) : (
                    <CheckCircle className="h-4 w-4 text-emerald-500" />
                  )}
                  配置包导入结果
                </div>
                <p className="mt-1 text-xs text-muted-foreground">
                  设置 {importResult.settings}、厂商账户 {importResult.vendorAccounts}、模型 {importResult.modelConfigs}、分类 {importResult.categories}、工具 {importResult.tools}、字段 {importResult.fields}、提示词版本 {importResult.promptVersions}、工作流 {importResult.workflows}
                </p>
              </div>
              <Button variant="ghost" size="sm" onClick={() => setImportResult(null)}>
                关闭
              </Button>
            </div>
            {importResult.warnings?.length ? (
              <div className="mt-4 rounded-md border border-amber-500/25 bg-amber-500/5 p-3">
                <div className="mb-2 text-xs font-medium text-amber-600 dark:text-amber-300">
                  发现 {importResult.warnings.length} 条需要处理的问题
                </div>
                <ul className="max-h-72 space-y-1 overflow-auto text-xs text-muted-foreground">
                  {importResult.warnings.slice(0, 20).map((warning, index) => (
                    <li key={`${warning}-${index}`} className="rounded bg-background/60 px-2 py-1">
                      {warning}
                    </li>
                  ))}
                </ul>
                {importResult.warnings.length > 20 ? (
                  <p className="mt-2 text-xs text-muted-foreground">
                    还有 {importResult.warnings.length - 20} 条未显示，请根据后端日志或重新导入结果继续排查。
                  </p>
                ) : null}
              </div>
            ) : null}
          </section>
        ) : null}

        <Tabs defaultValue="model" className="space-y-6">
          <TabsList className="flex h-auto flex-wrap justify-start gap-2 bg-transparent p-0">
            <TabsTrigger value="model" className="gap-2">
              <Settings2 className="h-4 w-4" />
              模型 API 中心
            </TabsTrigger>
            <TabsTrigger value="engine-api" className="gap-2">
              <KeyRound className="h-4 w-4" />
              引擎 API
            </TabsTrigger>
            <TabsTrigger value="agent-runtime" className="gap-2">
              <Bot className="h-4 w-4" />
              Agent 参数
            </TabsTrigger>
            <TabsTrigger value="agent-memory" className="gap-2">
              <Brain className="h-4 w-4" />
              记忆管理
            </TabsTrigger>
            <TabsTrigger value="system" className="gap-2">
              <Server className="h-4 w-4" />
              基础设置
            </TabsTrigger>
            <TabsTrigger value="features" className="gap-2">
              <Database className="h-4 w-4" />
              功能开关
            </TabsTrigger>
            <TabsTrigger value="customer-service" className="gap-2">
              <Headphones className="h-4 w-4" />
              客服设置
            </TabsTrigger>
            <TabsTrigger value="security" className="gap-2">
              <Shield className="h-4 w-4" />
              安全设置
            </TabsTrigger>
          </TabsList>

          <TabsContent value="model">
            <UnifiedApiSettings refreshKey={modelRefreshKey} />
          </TabsContent>

          <TabsContent value="engine-api">
            <EngineApiSettings refreshKey={modelRefreshKey} />
          </TabsContent>

          <TabsContent value="agent-runtime">
            <AgentRuntimeSettings />
          </TabsContent>

          <TabsContent value="agent-memory">
            <AgentMemoryManagement />
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

          <TabsContent value="customer-service" className="space-y-5">
            <section className="rounded-lg border border-border bg-card p-5">
              <div className="flex items-center justify-between rounded-md bg-secondary p-4">
                <div>
                  <p className="font-medium">启用用户端联系客服</p>
                  <p className="text-sm text-muted-foreground">开启后，用户端右上角会展示“联系客服”入口。</p>
                </div>
                <Switch
                  checked={form.customerServiceEnabled}
                  onCheckedChange={(value) => updateForm("customerServiceEnabled", value)}
                />
              </div>

              <div className="mt-5 grid gap-5 md:grid-cols-2">
                <div className="space-y-2">
                  <Label>入口标题</Label>
                  <Input
                    value={form.customerServiceTitle}
                    onChange={(event) => updateForm("customerServiceTitle", event.target.value)}
                    placeholder="联系客服"
                  />
                </div>
                <div className="space-y-2">
                  <Label>弹窗说明</Label>
                  <Textarea
                    value={form.customerServiceDescription}
                    onChange={(event) => updateForm("customerServiceDescription", event.target.value)}
                    placeholder="扫码添加客服，获取使用支持"
                    className="min-h-[88px]"
                  />
                </div>
              </div>

              <div className="mt-5 space-y-3">
                <Label>客服二维码</Label>
                <div className="grid gap-5 md:grid-cols-[minmax(0,1fr)_200px]">
                  <label
                    className={cn(
                      "flex cursor-pointer flex-col items-center justify-center rounded-lg border border-dashed border-border bg-secondary/30 px-4 py-8 text-center transition hover:border-primary/60 hover:bg-secondary/50",
                      qrUploading && "pointer-events-none opacity-70",
                    )}
                    onDragOver={(event) => event.preventDefault()}
                    onDrop={(event) => {
                      event.preventDefault()
                      void handleQrUpload(event.dataTransfer.files?.[0])
                    }}
                  >
                    <Upload className="mb-2 h-5 w-5 text-primary" />
                    <span className="text-sm font-medium">{qrUploading ? "上传中..." : "点击选择或拖拽图片到此处"}</span>
                    <span className="mt-1 text-xs text-muted-foreground">支持 JPG、PNG、WebP、GIF，最大 5MB；上传后自动保存</span>
                    <input
                      type="file"
                      accept="image/jpeg,image/png,image/webp,image/gif"
                      className="hidden"
                      disabled={qrUploading}
                      onChange={(event) => {
                        const file = event.target.files?.[0]
                        void handleQrUpload(file)
                        event.currentTarget.value = ""
                      }}
                    />
                  </label>
                  <div className="rounded-lg border border-border bg-secondary/50 p-3">
                    <p className="mb-2 text-sm font-medium">预览</p>
                    {form.customerServiceQrCodeUrl ? (
                      <img
                        src={resolveMediaUrl(form.customerServiceQrCodeUrl)}
                        alt="客服二维码预览"
                        className="aspect-square w-full rounded-md bg-white object-contain p-2"
                      />
                    ) : (
                      <div className="flex aspect-square w-full items-center justify-center rounded-md border border-dashed border-border text-center text-xs text-muted-foreground">
                        尚未上传
                      </div>
                    )}
                  </div>
                </div>
                {form.customerServiceQrCodeUrl ? (
                  <div className="flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
                    <span className="truncate">当前地址：{form.customerServiceQrCodeUrl}</span>
                    <Button
                      type="button"
                      variant="ghost"
                      size="sm"
                      className="h-7 px-2"
                      onClick={async () => {
                        updateForm("customerServiceQrCodeUrl", "")
                        try {
                          await updateSettings({ "customerService.qrCodeUrl": "" })
                          setNotice("已清除客服二维码")
                          setTimeout(() => setNotice(null), 2000)
                        } catch (err) {
                          setError(err instanceof ApiError ? err.message : "清除客服二维码失败")
                        }
                      }}
                    >
                      清除
                    </Button>
                  </div>
                ) : null}
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
            {importing ? <Loader2 className="h-4 w-4 animate-spin" /> : <Upload className="h-4 w-4" />}
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
