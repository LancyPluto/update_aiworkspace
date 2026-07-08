"use client"

import { AdminHeader } from "@/components/admin/header"
import { AdminLayout } from "@/components/admin/admin-layout"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Activity, ArrowUpRight, Gauge, LockKeyhole, Server, ShieldCheck } from "lucide-react"

const grafanaPath = "/grafana/"

export default function MonitoringPage() {
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
