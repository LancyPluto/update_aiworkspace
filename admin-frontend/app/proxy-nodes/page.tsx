"use client"

import { useCallback, useEffect, useState } from "react"
import { AdminHeader } from "@/components/admin/header"
import { AdminLayout } from "@/components/admin/admin-layout"
import { ProxyDomainAllowlist } from "@/components/proxy/proxy-domain-allowlist"
import { ProxyNodeManager } from "@/components/proxy/proxy-node-manager"
import { Alert, AlertDescription } from "@/components/ui/alert"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs"
import { ApiError } from "@/lib/api/http"
import {
  fetchMihomoNodes,
  fetchMihomoRuntime,
  type MihomoNodeList,
  type MihomoRuntimeStatus,
} from "@/lib/api/proxy-config"
import {
  Activity,
  CloudCog,
  Globe2,
  Loader2,
  Network,
  RefreshCw,
  Route,
  Server,
  TriangleAlert,
} from "lucide-react"

export default function ProxyNodesPage() {
  const [runtime, setRuntime] = useState<MihomoRuntimeStatus | null>(null)
  const [nodes, setNodes] = useState<MihomoNodeList | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  const loadStatus = useCallback(async () => {
    setLoading(true)
    setError(null)
    const [runtimeResult, nodeResult] = await Promise.allSettled([
      fetchMihomoRuntime(),
      fetchMihomoNodes(),
    ])
    if (runtimeResult.status === "fulfilled") setRuntime(runtimeResult.value)
    if (nodeResult.status === "fulfilled") setNodes(nodeResult.value)
    if (runtimeResult.status === "rejected" && nodeResult.status === "rejected") {
      const reason = runtimeResult.reason
      setError(reason instanceof ApiError ? reason.message : "代理运行状态加载失败")
    }
    setLoading(false)
  }, [])

  useEffect(() => {
    void loadStatus()
  }, [loadStatus])

  return (
    <AdminLayout>
      <AdminHeader
        title="代理配置"
        description="管理项目容器访问公网时使用的代理网站与出口节点"
      />

      <div className="space-y-5 p-4 sm:p-6">
        {error && (
          <Alert variant="destructive">
            <TriangleAlert className="h-4 w-4" />
            <AlertDescription>{error}</AlertDescription>
          </Alert>
        )}

        <section className="overflow-hidden rounded-lg border bg-card">
          <div className="flex flex-col gap-4 border-b px-5 py-4 lg:flex-row lg:items-center lg:justify-between">
            <div className="flex min-w-0 items-center gap-3">
              <div className={`flex h-10 w-10 shrink-0 items-center justify-center rounded-lg ${runtime?.available ? "bg-emerald-500/10 text-emerald-600 dark:text-emerald-400" : "bg-muted text-muted-foreground"}`}>
                <Network className="h-5 w-5" />
              </div>
              <div className="min-w-0">
                <div className="flex flex-wrap items-center gap-2">
                  <h2 className="text-base font-semibold">公网代理出口</h2>
                  <Badge variant={runtime?.available ? "default" : "secondary"}>
                    {loading ? "检测中" : runtime?.available ? "运行正常" : "不可用"}
                  </Badge>
                </div>
                <p className="mt-1 truncate text-xs text-muted-foreground">{nodes?.activeNode || "尚未选择可用节点"}</p>
              </div>
            </div>
            <Button variant="outline" onClick={() => void loadStatus()} disabled={loading}>
              {loading ? <Loader2 className="h-4 w-4 animate-spin" /> : <RefreshCw className="h-4 w-4" />}
              刷新状态
            </Button>
          </div>

          <div className="grid divide-y sm:grid-cols-2 sm:divide-x sm:divide-y-0 xl:grid-cols-4">
            <StatusItem icon={Activity} label="Mihomo" value={runtime?.available ? runtime.version || "在线" : "离线"} ok={runtime?.available} />
            <StatusItem icon={CloudCog} label="节点来源" value={nodes?.sourceType === "MANUAL" ? "云服务器" : "机场订阅"} ok={Boolean(nodes)} />
            <StatusItem icon={Route} label="节点选择" value={nodes?.selectionMode === "MANUAL" ? "手动固定" : "自动最快"} ok={nodes?.available} />
            <StatusItem icon={Server} label="最近应用" value={runtime?.lastAppliedAt ? formatTime(runtime.lastAppliedAt) : "暂无记录"} ok={Boolean(runtime?.lastAppliedAt)} />
          </div>
        </section>

        <Tabs defaultValue="websites" className="space-y-4">
          <TabsList className="grid h-10 w-full max-w-md grid-cols-2">
            <TabsTrigger value="websites"><Globe2 className="h-4 w-4" />网站分流</TabsTrigger>
            <TabsTrigger value="nodes"><CloudCog className="h-4 w-4" />代理节点</TabsTrigger>
          </TabsList>
          <TabsContent value="websites" className="mt-0">
            <ProxyDomainAllowlist onApplied={() => void loadStatus()} />
          </TabsContent>
          <TabsContent value="nodes" className="mt-0">
            <ProxyNodeManager onChanged={() => void loadStatus()} />
          </TabsContent>
        </Tabs>
      </div>
    </AdminLayout>
  )
}

function StatusItem({ icon: Icon, label, value, ok }: {
  icon: typeof Activity
  label: string
  value: string
  ok?: boolean
}) {
  return (
    <div className="flex min-h-20 min-w-0 items-center gap-3 px-5 py-4">
      <Icon className={`h-4 w-4 shrink-0 ${ok ? "text-emerald-600 dark:text-emerald-400" : "text-muted-foreground"}`} />
      <div className="min-w-0">
        <p className="text-xs text-muted-foreground">{label}</p>
        <p className="mt-1 truncate text-sm font-medium">{value}</p>
      </div>
    </div>
  )
}

function formatTime(value: string) {
  return new Date(value).toLocaleString("zh-CN", {
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    hour12: false,
  })
}
