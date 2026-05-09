"use client"

import { useState } from "react"
import { AdminLayout } from "@/components/admin/admin-layout"
import { AdminHeader } from "@/components/admin/header"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Switch } from "@/components/ui/switch"
import { Textarea } from "@/components/ui/textarea"
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select"
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs"
import {
  Key,
  Shield,
  Bell,
  Database,
  Server,
  Save,
  Eye,
  EyeOff,
  RefreshCw,
  CheckCircle,
} from "lucide-react"
import { cn } from "@/lib/utils"

export default function SettingsPage() {
  const [showApiKey, setShowApiKey] = useState(false)
  const [isSaving, setIsSaving] = useState(false)
  const [saved, setSaved] = useState(false)

  const handleSave = () => {
    setIsSaving(true)
    setTimeout(() => {
      setIsSaving(false)
      setSaved(true)
      setTimeout(() => setSaved(false), 2000)
    }, 1000)
  }

  return (
    <AdminLayout>
      <AdminHeader
        title="系统配置"
        description="管理系统设置和 API 密钥"
      />

      <div className="p-6">
        <Tabs defaultValue="api" className="space-y-6">
          <TabsList className="bg-secondary">
            <TabsTrigger value="api" className="gap-2">
              <Key className="h-4 w-4" />
              API 配置
            </TabsTrigger>
            <TabsTrigger value="system" className="gap-2">
              <Server className="h-4 w-4" />
              系统设置
            </TabsTrigger>
            <TabsTrigger value="security" className="gap-2">
              <Shield className="h-4 w-4" />
              安全设置
            </TabsTrigger>
            <TabsTrigger value="notification" className="gap-2">
              <Bell className="h-4 w-4" />
              通知设置
            </TabsTrigger>
          </TabsList>

          {/* API Configuration */}
          <TabsContent value="api" className="space-y-6">
            <div className="rounded-xl border border-border bg-card p-6">
              <h3 className="text-lg font-semibold text-card-foreground mb-4">
                AI 模型配置
              </h3>
              <div className="space-y-6">
                <div className="grid gap-6 md:grid-cols-2">
                  <div className="space-y-2">
                    <Label>默认模型</Label>
                    <Select defaultValue="gpt-4">
                      <SelectTrigger className="bg-secondary border-0">
                        <SelectValue />
                      </SelectTrigger>
                      <SelectContent className="bg-card border-border">
                        <SelectItem value="gpt-4">GPT-4</SelectItem>
                        <SelectItem value="gpt-3.5">GPT-3.5 Turbo</SelectItem>
                        <SelectItem value="claude-3">Claude 3</SelectItem>
                        <SelectItem value="gemini">Gemini Pro</SelectItem>
                      </SelectContent>
                    </Select>
                  </div>
                  <div className="space-y-2">
                    <Label>备用模型</Label>
                    <Select defaultValue="gpt-3.5">
                      <SelectTrigger className="bg-secondary border-0">
                        <SelectValue />
                      </SelectTrigger>
                      <SelectContent className="bg-card border-border">
                        <SelectItem value="gpt-4">GPT-4</SelectItem>
                        <SelectItem value="gpt-3.5">GPT-3.5 Turbo</SelectItem>
                        <SelectItem value="claude-3">Claude 3</SelectItem>
                        <SelectItem value="gemini">Gemini Pro</SelectItem>
                      </SelectContent>
                    </Select>
                  </div>
                </div>

                <div className="space-y-2">
                  <Label>OpenAI API Key</Label>
                  <div className="flex gap-2">
                    <div className="relative flex-1">
                      <Input
                        type={showApiKey ? "text" : "password"}
                        defaultValue="sk-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"
                        className="bg-secondary border-0 pr-10 font-mono"
                      />
                      <button
                        type="button"
                        onClick={() => setShowApiKey(!showApiKey)}
                        className="absolute right-3 top-1/2 -translate-y-1/2 text-muted-foreground hover:text-foreground"
                      >
                        {showApiKey ? (
                          <EyeOff className="h-4 w-4" />
                        ) : (
                          <Eye className="h-4 w-4" />
                        )}
                      </button>
                    </div>
                    <Button variant="outline" size="icon">
                      <RefreshCw className="h-4 w-4" />
                    </Button>
                  </div>
                  <p className="text-xs text-muted-foreground">
                    用于调用 OpenAI API 的密钥
                  </p>
                </div>

                <div className="space-y-2">
                  <Label>Anthropic API Key</Label>
                  <div className="flex gap-2">
                    <Input
                      type="password"
                      placeholder="sk-ant-xxxxxxxx"
                      className="bg-secondary border-0 font-mono"
                    />
                    <Button variant="outline" size="icon">
                      <RefreshCw className="h-4 w-4" />
                    </Button>
                  </div>
                </div>

                <div className="grid gap-4 md:grid-cols-3">
                  <div className="space-y-2">
                    <Label>Temperature</Label>
                    <Input
                      type="number"
                      defaultValue="0.7"
                      step="0.1"
                      min="0"
                      max="2"
                      className="bg-secondary border-0"
                    />
                  </div>
                  <div className="space-y-2">
                    <Label>Max Tokens</Label>
                    <Input
                      type="number"
                      defaultValue="2048"
                      className="bg-secondary border-0"
                    />
                  </div>
                  <div className="space-y-2">
                    <Label>请求超时 (秒)</Label>
                    <Input
                      type="number"
                      defaultValue="30"
                      className="bg-secondary border-0"
                    />
                  </div>
                </div>
              </div>
            </div>

            <div className="rounded-xl border border-border bg-card p-6">
              <h3 className="text-lg font-semibold text-card-foreground mb-4">
                API 限流配置
              </h3>
              <div className="grid gap-6 md:grid-cols-2">
                <div className="space-y-2">
                  <Label>单用户每分钟请求数</Label>
                  <Input
                    type="number"
                    defaultValue="10"
                    className="bg-secondary border-0"
                  />
                </div>
                <div className="space-y-2">
                  <Label>全局每分钟请求数</Label>
                  <Input
                    type="number"
                    defaultValue="100"
                    className="bg-secondary border-0"
                  />
                </div>
              </div>
            </div>
          </TabsContent>

          {/* System Settings */}
          <TabsContent value="system" className="space-y-6">
            <div className="rounded-xl border border-border bg-card p-6">
              <h3 className="text-lg font-semibold text-card-foreground mb-4">
                基础配置
              </h3>
              <div className="space-y-6">
                <div className="space-y-2">
                  <Label>平台名称</Label>
                  <Input
                    defaultValue="AI 工具超市"
                    className="bg-secondary border-0"
                  />
                </div>
                <div className="space-y-2">
                  <Label>平台描述</Label>
                  <Textarea
                    defaultValue="一站式 AI 经营助手，帮助企业提升效率"
                    className="bg-secondary border-0"
                  />
                </div>
                <div className="grid gap-6 md:grid-cols-2">
                  <div className="space-y-2">
                    <Label>新用户免费算力</Label>
                    <Input
                      type="number"
                      defaultValue="100"
                      className="bg-secondary border-0"
                    />
                  </div>
                  <div className="space-y-2">
                    <Label>任务最大重试次数</Label>
                    <Input
                      type="number"
                      defaultValue="3"
                      className="bg-secondary border-0"
                    />
                  </div>
                </div>
              </div>
            </div>

            <div className="rounded-xl border border-border bg-card p-6">
              <h3 className="text-lg font-semibold text-card-foreground mb-4">
                功能开关
              </h3>
              <div className="space-y-4">
                {[
                  { label: "启用用户注册", description: "允许新用户注册账号", enabled: true },
                  { label: "启用任务队列", description: "使用 Redis 队列异步执行任务", enabled: true },
                  { label: "启用 SSE 推送", description: "实时推送任务状态更新", enabled: true },
                  { label: "启用算力扣除", description: "任务完成后自动扣除算力", enabled: true },
                  { label: "维护模式", description: "开启后用户无法访问系统", enabled: false },
                ].map((item) => (
                  <div
                    key={item.label}
                    className="flex items-center justify-between rounded-lg bg-secondary p-4"
                  >
                    <div>
                      <p className="font-medium">{item.label}</p>
                      <p className="text-sm text-muted-foreground">
                        {item.description}
                      </p>
                    </div>
                    <Switch defaultChecked={item.enabled} />
                  </div>
                ))}
              </div>
            </div>
          </TabsContent>

          {/* Security Settings */}
          <TabsContent value="security" className="space-y-6">
            <div className="rounded-xl border border-border bg-card p-6">
              <h3 className="text-lg font-semibold text-card-foreground mb-4">
                安全配置
              </h3>
              <div className="space-y-6">
                <div className="grid gap-6 md:grid-cols-2">
                  <div className="space-y-2">
                    <Label>JWT 过期时间 (小时)</Label>
                    <Input
                      type="number"
                      defaultValue="24"
                      className="bg-secondary border-0"
                    />
                  </div>
                  <div className="space-y-2">
                    <Label>登录失败锁定次数</Label>
                    <Input
                      type="number"
                      defaultValue="5"
                      className="bg-secondary border-0"
                    />
                  </div>
                </div>
                <div className="space-y-2">
                  <Label>允许的域名 (CORS)</Label>
                  <Textarea
                    defaultValue="https://ai-tools.com&#10;https://www.ai-tools.com"
                    className="bg-secondary border-0 font-mono"
                  />
                  <p className="text-xs text-muted-foreground">
                    每行一个域名
                  </p>
                </div>
              </div>
            </div>

            <div className="rounded-xl border border-border bg-card p-6">
              <h3 className="text-lg font-semibold text-card-foreground mb-4">
                安全开关
              </h3>
              <div className="space-y-4">
                {[
                  { label: "启用验证码", description: "登录时需要验证码", enabled: true },
                  { label: "启用两步验证", description: "管理员登录需要两步验证", enabled: false },
                  { label: "记录操作日志", description: "记录所有管理员操作", enabled: true },
                  { label: "IP 白名单", description: "限制管理后台访问 IP", enabled: false },
                ].map((item) => (
                  <div
                    key={item.label}
                    className="flex items-center justify-between rounded-lg bg-secondary p-4"
                  >
                    <div>
                      <p className="font-medium">{item.label}</p>
                      <p className="text-sm text-muted-foreground">
                        {item.description}
                      </p>
                    </div>
                    <Switch defaultChecked={item.enabled} />
                  </div>
                ))}
              </div>
            </div>
          </TabsContent>

          {/* Notification Settings */}
          <TabsContent value="notification" className="space-y-6">
            <div className="rounded-xl border border-border bg-card p-6">
              <h3 className="text-lg font-semibold text-card-foreground mb-4">
                通知配置
              </h3>
              <div className="space-y-6">
                <div className="space-y-2">
                  <Label>管理员邮箱</Label>
                  <Input
                    type="email"
                    defaultValue="admin@ai-tools.com"
                    className="bg-secondary border-0"
                  />
                </div>
                <div className="grid gap-6 md:grid-cols-2">
                  <div className="space-y-2">
                    <Label>SMTP 服务器</Label>
                    <Input
                      defaultValue="smtp.example.com"
                      className="bg-secondary border-0"
                    />
                  </div>
                  <div className="space-y-2">
                    <Label>SMTP 端口</Label>
                    <Input
                      type="number"
                      defaultValue="465"
                      className="bg-secondary border-0"
                    />
                  </div>
                </div>
              </div>
            </div>

            <div className="rounded-xl border border-border bg-card p-6">
              <h3 className="text-lg font-semibold text-card-foreground mb-4">
                通知开关
              </h3>
              <div className="space-y-4">
                {[
                  { label: "新用户注册通知", description: "有新用户注册时发送邮件", enabled: true },
                  { label: "订单支付通知", description: "用户支付成功时发送邮件", enabled: true },
                  { label: "任务失败告警", description: "任务失败率超过阈值时告警", enabled: true },
                  { label: "API 额度告警", description: "API 消耗接近限额时告警", enabled: true },
                ].map((item) => (
                  <div
                    key={item.label}
                    className="flex items-center justify-between rounded-lg bg-secondary p-4"
                  >
                    <div>
                      <p className="font-medium">{item.label}</p>
                      <p className="text-sm text-muted-foreground">
                        {item.description}
                      </p>
                    </div>
                    <Switch defaultChecked={item.enabled} />
                  </div>
                ))}
              </div>
            </div>
          </TabsContent>
        </Tabs>

        {/* Save Button */}
        <div className="mt-6 flex justify-end">
          <Button
            onClick={handleSave}
            disabled={isSaving}
            className="gap-2 min-w-[120px]"
          >
            {saved ? (
              <>
                <CheckCircle className="h-4 w-4" />
                已保存
              </>
            ) : isSaving ? (
              <>
                <RefreshCw className="h-4 w-4 animate-spin" />
                保存中...
              </>
            ) : (
              <>
                <Save className="h-4 w-4" />
                保存配置
              </>
            )}
          </Button>
        </div>
      </div>
    </AdminLayout>
  )
}
