"use client"

import { useEffect, useState } from "react"

import { AdminHeader } from "@/components/admin/header"
import { AdminLayout } from "@/components/admin/admin-layout"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Activity, AlertTriangle, ArrowUpRight, CheckCircle2, Gauge, Image as ImageIcon, LockKeyhole, Server, ShieldCheck } from "lucide-react"
import { fetchMediaDeliveryStatus, type MediaDeliveryStatus } from "@/lib/api/media-delivery"

const grafanaPath = "/grafana/"

export default function MonitoringPage() {
  const [mediaStatus, setMediaStatus] = useState<MediaDeliveryStatus | null>(null)
  const [mediaError, setMediaError] = useState("")

  useEffect(() => {
    fetchMediaDeliveryStatus().then(setMediaStatus).catch((error) => {
      setMediaError(error instanceof Error ? error.message : "媒体交付状态读取失败")
    })
  }, [])

  return (
    <AdminLayout>
      <AdminHeader
        title="系统监控"
        description="Prometheus + Grafana 生产监控入口，面板独立打开，不在管理后台中嵌入。"
      />

      <div className="space-y-6 p-6">
        <Card className="overflow-hidden border-primary/20 bg-gradient-to-br from-primary/10 via-card to-accent/10">
          <CardHeader>
            <div className="flex flex-wrap items-start justify-between gap-4">
              <div className="space-y-2">
                <div className="flex items-center gap-2">
                  <Badge variant="secondary" className="gap-1">
                    <ShieldCheck className="h-3.5 w-3.5" />
                    HTTPS /grafana/
                  </Badge>
                  <Badge variant="outline">独立登录</Badge>
                </div>
                <CardTitle className="text-2xl">Grafana 监控面板</CardTitle>
                <CardDescription className="max-w-2xl">
                  生产入口通过 Nginx 反代到 Grafana 子路径。Prometheus 不开放公网入口，避免暴露内部指标。
                </CardDescription>
              </div>
              <Button asChild size="lg" className="shrink-0">
                <a href={grafanaPath} target="_blank" rel="noreferrer">
                  打开 Grafana
                  <ArrowUpRight className="h-4 w-4" />
                </a>
              </Button>
            </div>
          </CardHeader>
        </Card>

        <div className="grid gap-4 md:grid-cols-3">
          <Card>
            <CardHeader>
              <CardTitle className="flex items-center gap-2 text-base">
                <Gauge className="h-4 w-4 text-primary" />
                基础设施
              </CardTitle>
              <CardDescription>主机 CPU、内存、磁盘，以及 Docker 容器资源。</CardDescription>
            </CardHeader>
          </Card>
          <Card>
            <CardHeader>
              <CardTitle className="flex items-center gap-2 text-base">
                <Activity className="h-4 w-4 text-accent" />
                服务探活
              </CardTitle>
              <CardDescription>Nginx、Backend、Agent、MySQL、Redis、RabbitMQ 和代理端口。</CardDescription>
            </CardHeader>
          </Card>
          <Card>
            <CardHeader>
              <CardTitle className="flex items-center gap-2 text-base">
                <LockKeyhole className="h-4 w-4 text-chart-3" />
                安全边界
              </CardTitle>
              <CardDescription>Grafana 需要独立登录；Prometheus 只允许服务器本机访问。</CardDescription>
            </CardHeader>
          </Card>
        </div>

        <section className="space-y-4" aria-labelledby="media-delivery-title">
          <div className="flex flex-wrap items-end justify-between gap-3">
            <div>
              <h2 id="media-delivery-title" className="flex items-center gap-2 text-lg font-semibold">
                <ImageIcon className="h-5 w-5 text-primary" />
                媒体交付
              </h2>
              <p className="text-sm text-muted-foreground">OSS/CDN、派生图片与缓存策略的脱敏运行配置。</p>
            </div>
            {mediaStatus ? <Badge variant="outline">策略 {mediaStatus.policyVersion}</Badge> : null}
          </div>

          {mediaError ? (
            <div className="flex items-center gap-2 rounded-md border border-destructive/30 bg-destructive/10 p-4 text-sm text-destructive">
              <AlertTriangle className="h-4 w-4" /> {mediaError}
            </div>
          ) : null}

          <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-4">
            <MediaStatusCard label="存储模式" value={mediaStatus?.provider || "读取中"} healthy={Boolean(mediaStatus)} />
            <MediaStatusCard label="公共 CDN" value={mediaStatus?.publicHost || "未配置"} healthy={Boolean(mediaStatus?.publicHost)} />
            <MediaStatusCard label="图片派生" value={mediaStatus?.imageTransformEnabled ? "已启用" : "未启用"} healthy={Boolean(mediaStatus?.imageTransformEnabled)} />
            <MediaStatusCard label="私有 CDN 鉴权" value={mediaStatus?.privateCdnAuthConfigured ? "已配置" : "OSS 签名回退"} healthy={Boolean(mediaStatus)} />
          </div>

          {mediaStatus ? (
            <div className="grid gap-3 rounded-md border bg-card p-4 text-sm md:grid-cols-3">
              <CachePolicy label="公共哈希资源" value={mediaStatus.publicCacheControl} />
              <CachePolicy label="私有资源" value={mediaStatus.privateCacheControl} />
              <CachePolicy label="历史资源" value={mediaStatus.legacyCacheControl} />
              {mediaStatus.issues.map((issue) => (
                <p key={issue} className="flex items-start gap-2 text-amber-600 md:col-span-3">
                  <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" /> {issue}
                </p>
              ))}
            </div>
          ) : null}
        </section>

        <Card>
          <CardHeader>
            <CardTitle className="flex items-center gap-2">
              <Server className="h-5 w-5" />
              运维说明
            </CardTitle>
            <CardDescription>如果页面无法打开，优先按下面顺序排查。</CardDescription>
          </CardHeader>
          <CardContent>
            <ol className="list-decimal space-y-2 pl-5 text-sm text-muted-foreground">
              <li>确认生产监控容器已启动：grafana、prometheus、node-exporter、cadvisor、blackbox-exporter。</li>
              <li>确认服务器本机健康检查：Grafana 的 <code className="rounded bg-muted px-1">/api/health</code>、Prometheus 的 <code className="rounded bg-muted px-1">/-/ready</code>。</li>
              <li>确认 Nginx 已加载包含 <code className="rounded bg-muted px-1">/grafana/</code> 的配置。</li>
              <li>首次登录后请立即修改 Grafana 管理员密码，生产不要使用示例密码。</li>
            </ol>
          </CardContent>
        </Card>
      </div>
    </AdminLayout>
  )
}

function MediaStatusCard({ label, value, healthy }: { label: string; value: string; healthy: boolean }) {
  return (
    <div className="rounded-md border bg-card p-4">
      <div className="flex items-center justify-between gap-3">
        <p className="text-sm text-muted-foreground">{label}</p>
        {healthy ? <CheckCircle2 className="h-4 w-4 text-emerald-500" /> : <AlertTriangle className="h-4 w-4 text-amber-500" />}
      </div>
      <p className="mt-2 break-words font-medium">{value}</p>
    </div>
  )
}

function CachePolicy({ label, value }: { label: string; value: string }) {
  return <div><p className="text-muted-foreground">{label}</p><code className="mt-1 block break-all text-xs">{value}</code></div>
}
