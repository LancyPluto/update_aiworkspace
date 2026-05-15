"use client"

import { useEffect, useState } from "react"
import { AdminLayout } from "@/components/admin/admin-layout"
import {
  fetchCommerceNodes,
  fetchCommerceOrders,
  fetchCommercePlans,
  fetchCommercePools,
  fetchModelCapacityStats,
  fetchModelFeedback,
  fetchModelCallLogs,
  fetchQueueStats,
  healthCheckCommerceNodes,
  healthCheckCommerceNode,
  requeueDeadMessages,
  updateCommerceNodeStatus,
  upsertCommerceNode,
  upsertCommercePlan,
  upsertCommercePool,
  type CommerceRecord,
  type ModelCapacityStats,
  type QueueStats,
} from "@/lib/api/commerce"

function value(row: CommerceRecord, key: string) {
  const current = row[key]
  return current === undefined || current === null ? "-" : String(current)
}

function warningClass(level: string) {
  if (level === "CRITICAL") return "bg-red-50 text-red-700 ring-red-200"
  if (level === "WARN") return "bg-amber-50 text-amber-700 ring-amber-200"
  return "bg-emerald-50 text-emerald-700 ring-emerald-200"
}

function percentValue(row: CommerceRecord, key: string) {
  const current = row[key]
  return current === undefined || current === null ? "-" : `${current}%`
}

function DataTable({ title, rows, columns }: { title: string; rows: CommerceRecord[]; columns: string[] }) {
  return (
    <section className="rounded-2xl border border-slate-200 bg-white shadow-sm">
      <div className="border-b border-slate-100 px-5 py-4">
        <h2 className="font-semibold text-slate-950">{title}</h2>
      </div>
      <div className="overflow-x-auto">
        <table className="w-full min-w-[720px] text-sm">
          <thead className="bg-slate-50 text-xs uppercase tracking-wide text-slate-500">
            <tr>
              {columns.map((column) => (
                <th key={column} className="px-4 py-3 text-left font-medium">{column}</th>
              ))}
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100">
            {rows.map((row, index) => (
              <tr key={`${title}-${index}`}>
                {columns.map((column) => (
                  <td key={column} className="px-4 py-3 text-slate-700">{value(row, column)}</td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
        {rows.length === 0 && <div className="px-5 py-8 text-center text-sm text-slate-500">No records</div>}
      </div>
    </section>
  )
}

function NodesTable({
  rows,
  onStatus,
  onHealthCheck,
  saving,
}: {
  rows: CommerceRecord[]
  onStatus: (nodeId: number, status: string) => void
  onHealthCheck: (nodeId: number) => void
  saving: string
}) {
  return (
    <section className="rounded-2xl border border-slate-200 bg-white shadow-sm">
      <div className="border-b border-slate-100 px-5 py-4">
        <h2 className="font-semibold text-slate-950">Nodes</h2>
      </div>
      <div className="overflow-x-auto">
        <table className="w-full min-w-[900px] text-sm">
          <thead className="bg-slate-50 text-xs uppercase tracking-wide text-slate-500">
            <tr>
              {[
                "id",
                "node_code",
                "pool_id",
                "status",
                "health_status",
                "concurrency",
                "daily_usage",
                "warning",
                "last_error",
                "actions",
              ].map((column) => (
                <th key={column} className="px-4 py-3 text-left font-medium">{column}</th>
              ))}
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100">
            {rows.map((row) => {
              const id = Number(row.id)
              const warningLevel = value(row, "warning_level")
              return (
                <tr key={id}>
                  {["id", "node_code", "pool_id", "status", "health_status"].map((column) => (
                    <td key={column} className="px-4 py-3 text-slate-700">{value(row, column)}</td>
                  ))}
                  <td className="px-4 py-3 text-slate-700">
                    {value(row, "current_concurrency")} / {value(row, "max_concurrency")}
                    <div className="mt-1 text-xs text-slate-400">{percentValue(row, "concurrency_usage_percent")}</div>
                  </td>
                  <td className="px-4 py-3 text-slate-700">
                    {value(row, "today_used")} / {value(row, "daily_limit")}
                    <div className="mt-1 text-xs text-slate-400">{percentValue(row, "daily_usage_percent")}</div>
                  </td>
                  <td className="px-4 py-3">
                    <span className={`inline-flex rounded-full px-2 py-1 text-xs font-semibold ring-1 ${warningClass(warningLevel)}`}>
                      {warningLevel}
                    </span>
                    <div className="mt-1 text-xs text-slate-500">{value(row, "warning_reason")}</div>
                  </td>
                  <td className="max-w-[260px] truncate px-4 py-3 text-slate-500" title={value(row, "last_error")}>
                    {value(row, "last_error")}
                  </td>
                  <td className="px-4 py-3">
                    <div className="flex flex-wrap gap-2">
                      <button
                        className="rounded-lg bg-blue-600 px-3 py-1.5 text-xs font-semibold text-white disabled:opacity-50"
                        disabled={saving === `health-${id}`}
                        onClick={() => onHealthCheck(id)}
                      >
                        Check
                      </button>
                      <button
                        className="rounded-lg bg-emerald-600 px-3 py-1.5 text-xs font-semibold text-white disabled:opacity-50"
                        disabled={saving === `node-${id}`}
                        onClick={() => onStatus(id, "AVAILABLE")}
                      >
                        Online
                      </button>
                      <button
                        className="rounded-lg bg-slate-800 px-3 py-1.5 text-xs font-semibold text-white disabled:opacity-50"
                        disabled={saving === `node-${id}`}
                        onClick={() => onStatus(id, "DISABLED")}
                      >
                        Offline
                      </button>
                    </div>
                  </td>
                </tr>
              )
            })}
          </tbody>
        </table>
        {rows.length === 0 && <div className="px-5 py-8 text-center text-sm text-slate-500">No records</div>}
      </div>
    </section>
  )
}

function TextInput({
  label,
  value,
  onChange,
  placeholder,
  type = "text",
}: {
  label: string
  value: string
  onChange: (value: string) => void
  placeholder?: string
  type?: string
}) {
  return (
    <label className="space-y-1 text-sm">
      <span className="font-medium text-slate-700">{label}</span>
      <input
        type={type}
        value={value}
        placeholder={placeholder}
        onChange={(event) => onChange(event.target.value)}
        className="w-full rounded-xl border border-slate-200 px-3 py-2 text-sm outline-none focus:border-blue-400"
      />
    </label>
  )
}

export default function CommercePage() {
  const [plans, setPlans] = useState<CommerceRecord[]>([])
  const [pools, setPools] = useState<CommerceRecord[]>([])
  const [nodes, setNodes] = useState<CommerceRecord[]>([])
  const [orders, setOrders] = useState<CommerceRecord[]>([])
  const [logs, setLogs] = useState<CommerceRecord[]>([])
  const [feedback, setFeedback] = useState<CommerceRecord[]>([])
  const [queueStats, setQueueStats] = useState<QueueStats | null>(null)
  const [capacityStats, setCapacityStats] = useState<ModelCapacityStats | null>(null)
  const [error, setError] = useState("")
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState("")
  const [planForm, setPlanForm] = useState({
    planCode: "global_model_monthly",
    planName: "Global Model Monthly",
    planType: "MODEL_CHANNEL",
    durationDays: "30",
    priceCents: "16000",
    creditAmount: "0",
    status: "ACTIVE",
    description: "Monthly access to global model pools",
  })
  const [poolForm, setPoolForm] = useState({
    poolCode: "gpt_pool",
    poolName: "GPT Pool",
    provider: "openai",
    modelName: "gpt-4.1",
    specLabel: "PRO",
    status: "ACTIVE",
    sortOrder: "20",
    description: "",
  })
  const [nodeForm, setNodeForm] = useState({
    poolId: "",
    nodeCode: "",
    displayLabel: "",
    providerProtocol: "openai_compatible",
    baseUrl: "",
    apiKey: "",
    modelName: "",
    maxConcurrency: "1",
    hourlyLimit: "",
    dailyLimit: "",
    status: "AVAILABLE",
    healthStatus: "UNKNOWN",
    note: "",
  })

  async function load() {
    setLoading(true)
    setError("")
    try {
      const [plansRes, poolsRes, nodesRes, ordersRes, logsRes, feedbackRes, queueStatsRes, capacityStatsRes] = await Promise.all([
        fetchCommercePlans(),
        fetchCommercePools(),
        fetchCommerceNodes(),
        fetchCommerceOrders(),
        fetchModelCallLogs(),
        fetchModelFeedback(),
        fetchQueueStats(),
        fetchModelCapacityStats(),
      ])
      setPlans(plansRes)
      setPools(poolsRes)
      setNodes(nodesRes)
      setOrders(ordersRes)
      setLogs(logsRes)
      setFeedback(feedbackRes)
      setQueueStats(queueStatsRes)
      setCapacityStats(capacityStatsRes)
      if (!nodeForm.poolId && poolsRes[0]?.id) {
        setNodeForm((current) => ({ ...current, poolId: String(poolsRes[0].id) }))
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : "Load failed")
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    let cancelled = false
    async function loadIfActive() {
      if (!cancelled) await load()
    }
    loadIfActive()
    return () => {
      cancelled = true
    }
  }, [])

  async function submit(kind: "plan" | "pool" | "node") {
    setSaving(kind)
    setError("")
    try {
      if (kind === "plan") {
        await upsertCommercePlan({
          ...planForm,
          durationDays: Number(planForm.durationDays || 30),
          priceCents: Number(planForm.priceCents || 0),
          creditAmount: Number(planForm.creditAmount || 0),
        })
      } else if (kind === "pool") {
        await upsertCommercePool({
          ...poolForm,
          sortOrder: Number(poolForm.sortOrder || 0),
        })
      } else {
        await upsertCommerceNode({
          ...nodeForm,
          poolId: Number(nodeForm.poolId),
          maxConcurrency: Number(nodeForm.maxConcurrency || 1),
          hourlyLimit: nodeForm.hourlyLimit ? Number(nodeForm.hourlyLimit) : null,
          dailyLimit: nodeForm.dailyLimit ? Number(nodeForm.dailyLimit) : null,
        })
      }
      await load()
    } catch (err) {
      setError(err instanceof Error ? err.message : "Save failed")
    } finally {
      setSaving("")
    }
  }

  async function requeueDead() {
    setSaving("requeue")
    setError("")
    try {
      await requeueDeadMessages(10)
      const stats = await fetchQueueStats()
      setQueueStats(stats)
    } catch (err) {
      setError(err instanceof Error ? err.message : "Requeue failed")
    } finally {
      setSaving("")
    }
  }

  async function updateNodeStatus(nodeId: number, status: string) {
    setSaving(`node-${nodeId}`)
    setError("")
    try {
      await updateCommerceNodeStatus(nodeId, status)
      const [nodesRes, capacityStatsRes] = await Promise.all([fetchCommerceNodes(), fetchModelCapacityStats()])
      setNodes(nodesRes)
      setCapacityStats(capacityStatsRes)
    } catch (err) {
      setError(err instanceof Error ? err.message : "Update node status failed")
    } finally {
      setSaving("")
    }
  }

  async function healthCheckNode(nodeId: number) {
    setSaving(`health-${nodeId}`)
    setError("")
    try {
      await healthCheckCommerceNode(nodeId)
      const [nodesRes, capacityStatsRes] = await Promise.all([fetchCommerceNodes(), fetchModelCapacityStats()])
      setNodes(nodesRes)
      setCapacityStats(capacityStatsRes)
    } catch (err) {
      setError(err instanceof Error ? err.message : "Health check failed")
    } finally {
      setSaving("")
    }
  }

  async function healthCheckNodes() {
    setSaving("health-batch")
    setError("")
    try {
      await healthCheckCommerceNodes()
      const [nodesRes, poolsRes, capacityStatsRes] = await Promise.all([
        fetchCommerceNodes(),
        fetchCommercePools(),
        fetchModelCapacityStats(),
      ])
      setNodes(nodesRes)
      setPools(poolsRes)
      setCapacityStats(capacityStatsRes)
    } catch (err) {
      setError(err instanceof Error ? err.message : "Batch health check failed")
    } finally {
      setSaving("")
    }
  }

  return (
    <AdminLayout>
      <div className="space-y-6 p-6">
        <div>
          <h1 className="text-2xl font-semibold text-slate-950">Global Model Operations</h1>
          <p className="mt-1 text-sm text-slate-500">Manage plans, pools, nodes, orders, and model call logs.</p>
        </div>
        {error && <div className="rounded-xl border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700">{error}</div>}
        <section className="rounded-2xl border border-slate-200 bg-white p-5 shadow-sm">
          <div className="flex flex-wrap items-center justify-between gap-3">
            <div>
              <h2 className="font-semibold text-slate-950">Model Channel Capacity</h2>
              <p className="mt-1 text-sm text-slate-500">Batch health checks auto-disable failed nodes and refresh capacity warnings.</p>
            </div>
            <button
              className="rounded-xl bg-blue-600 px-4 py-2 text-sm font-semibold text-white disabled:opacity-50"
              disabled={saving === "health-batch"}
              onClick={healthCheckNodes}
            >
              {saving === "health-batch" ? "Checking..." : "Batch health check"}
            </button>
          </div>
          <div className="mt-4 grid gap-3 md:grid-cols-2 xl:grid-cols-4">
            <div className="rounded-xl border border-slate-100 bg-slate-50 p-4">
              <div className="text-xs uppercase tracking-wide text-slate-400">Available Nodes</div>
              <div className="mt-2 font-semibold text-slate-900">{String(capacityStats?.available_nodes ?? 0)} / {String(capacityStats?.total_nodes ?? 0)}</div>
            </div>
            <div className="rounded-xl border border-slate-100 bg-slate-50 p-4">
              <div className="text-xs uppercase tracking-wide text-slate-400">Unhealthy</div>
              <div className="mt-2 font-semibold text-slate-900">{String(capacityStats?.unhealthy_nodes ?? 0)}</div>
              <div className="mt-1 text-sm text-slate-500">disabled: {String(capacityStats?.disabled_nodes ?? 0)}</div>
            </div>
            <div className="rounded-xl border border-slate-100 bg-slate-50 p-4">
              <div className="text-xs uppercase tracking-wide text-slate-400">Concurrency</div>
              <div className="mt-2 font-semibold text-slate-900">{String(capacityStats?.current_concurrency ?? 0)} / {String(capacityStats?.max_concurrency ?? 0)}</div>
              <div className="mt-1 text-sm text-slate-500">{String(capacityStats?.concurrency_usage_percent ?? 0)}% used</div>
            </div>
            <div className="rounded-xl border border-slate-100 bg-slate-50 p-4">
              <div className="text-xs uppercase tracking-wide text-slate-400">Daily Quota</div>
              <div className="mt-2 font-semibold text-slate-900">{String(capacityStats?.today_used ?? 0)} / {String(capacityStats?.daily_limit ?? 0)}</div>
              <div className="mt-1 text-sm text-slate-500">{String(capacityStats?.daily_usage_percent ?? 0)}% used</div>
            </div>
          </div>
        </section>
        <section className="rounded-2xl border border-slate-200 bg-white p-5 shadow-sm">
          <div className="flex flex-wrap items-center justify-between gap-3">
            <div>
              <h2 className="font-semibold text-slate-950">Queue Operations</h2>
              <p className="mt-1 text-sm text-slate-500">RabbitMQ task queue status and dead-letter recovery.</p>
            </div>
            <button
              className="rounded-xl bg-slate-950 px-4 py-2 text-sm font-semibold text-white disabled:opacity-50"
              disabled={saving === "requeue"}
              onClick={requeueDead}
            >
              {saving === "requeue" ? "Requeueing..." : "Requeue 10 dead messages"}
            </button>
          </div>
          <div className="mt-4 grid gap-3 md:grid-cols-2 xl:grid-cols-4">
            <div className="rounded-xl border border-slate-100 bg-slate-50 p-4">
              <div className="text-xs uppercase tracking-wide text-slate-400">Backend</div>
              <div className="mt-2 font-semibold text-slate-900">{queueStats?.backend || "-"}</div>
            </div>
            <div className="rounded-xl border border-slate-100 bg-slate-50 p-4">
              <div className="text-xs uppercase tracking-wide text-slate-400">Task Queue</div>
              <div className="mt-2 font-semibold text-slate-900">{String(queueStats?.taskQueue?.name || "-")}</div>
              <div className="mt-1 text-sm text-slate-500">messages: {String(queueStats?.taskQueue?.messageCount ?? 0)}</div>
            </div>
            <div className="rounded-xl border border-slate-100 bg-slate-50 p-4">
              <div className="text-xs uppercase tracking-wide text-slate-400">Dead Queue</div>
              <div className="mt-2 font-semibold text-slate-900">{String(queueStats?.deadQueue?.name || "-")}</div>
              <div className="mt-1 text-sm text-slate-500">messages: {String(queueStats?.deadQueue?.messageCount ?? 0)}</div>
            </div>
            <div className="rounded-xl border border-slate-100 bg-slate-50 p-4">
              <div className="text-xs uppercase tracking-wide text-slate-400">Consumers</div>
              <div className="mt-2 font-semibold text-slate-900">{String(queueStats?.taskQueue?.consumerCount ?? 0)}</div>
              <div className="mt-1 text-sm text-slate-500">{queueStats?.exchange || "-"}</div>
            </div>
          </div>
        </section>
        <section className="grid gap-4 xl:grid-cols-3">
          <div className="rounded-2xl border border-slate-200 bg-white p-5 shadow-sm">
            <h2 className="font-semibold text-slate-950">Plan</h2>
            <div className="mt-4 grid gap-3">
              <TextInput label="Code" value={planForm.planCode} onChange={(value) => setPlanForm({ ...planForm, planCode: value })} />
              <TextInput label="Name" value={planForm.planName} onChange={(value) => setPlanForm({ ...planForm, planName: value })} />
              <TextInput label="Type" value={planForm.planType} onChange={(value) => setPlanForm({ ...planForm, planType: value })} />
              <TextInput label="Days" type="number" value={planForm.durationDays} onChange={(value) => setPlanForm({ ...planForm, durationDays: value })} />
              <TextInput label="Price cents" type="number" value={planForm.priceCents} onChange={(value) => setPlanForm({ ...planForm, priceCents: value })} />
              <button className="rounded-xl bg-blue-600 px-4 py-2 text-sm font-semibold text-white disabled:opacity-50" disabled={saving === "plan"} onClick={() => submit("plan")}>
                {saving === "plan" ? "Saving..." : "Save plan"}
              </button>
            </div>
          </div>

          <div className="rounded-2xl border border-slate-200 bg-white p-5 shadow-sm">
            <h2 className="font-semibold text-slate-950">Pool</h2>
            <div className="mt-4 grid gap-3">
              <TextInput label="Code" value={poolForm.poolCode} onChange={(value) => setPoolForm({ ...poolForm, poolCode: value })} />
              <TextInput label="Name" value={poolForm.poolName} onChange={(value) => setPoolForm({ ...poolForm, poolName: value })} />
              <TextInput label="Provider" value={poolForm.provider} onChange={(value) => setPoolForm({ ...poolForm, provider: value })} />
              <TextInput label="Model" value={poolForm.modelName} onChange={(value) => setPoolForm({ ...poolForm, modelName: value })} />
              <TextInput label="Spec" value={poolForm.specLabel} onChange={(value) => setPoolForm({ ...poolForm, specLabel: value })} />
              <TextInput label="Status" value={poolForm.status} onChange={(value) => setPoolForm({ ...poolForm, status: value })} />
              <button className="rounded-xl bg-blue-600 px-4 py-2 text-sm font-semibold text-white disabled:opacity-50" disabled={saving === "pool"} onClick={() => submit("pool")}>
                {saving === "pool" ? "Saving..." : "Save pool"}
              </button>
            </div>
          </div>

          <div className="rounded-2xl border border-slate-200 bg-white p-5 shadow-sm">
            <h2 className="font-semibold text-slate-950">Node</h2>
            <div className="mt-4 grid gap-3">
              <TextInput label="Pool ID" type="number" value={nodeForm.poolId} onChange={(value) => setNodeForm({ ...nodeForm, poolId: value })} />
              <TextInput label="Node code" value={nodeForm.nodeCode} onChange={(value) => setNodeForm({ ...nodeForm, nodeCode: value })} placeholder="gpt-node-01" />
              <TextInput label="Protocol" value={nodeForm.providerProtocol} onChange={(value) => setNodeForm({ ...nodeForm, providerProtocol: value })} />
              <TextInput label="Base URL" value={nodeForm.baseUrl} onChange={(value) => setNodeForm({ ...nodeForm, baseUrl: value })} />
              <TextInput label="API Key" value={nodeForm.apiKey} onChange={(value) => setNodeForm({ ...nodeForm, apiKey: value })} />
              <TextInput label="Model" value={nodeForm.modelName} onChange={(value) => setNodeForm({ ...nodeForm, modelName: value })} />
              <TextInput label="Max concurrency" type="number" value={nodeForm.maxConcurrency} onChange={(value) => setNodeForm({ ...nodeForm, maxConcurrency: value })} />
              <TextInput label="Status" value={nodeForm.status} onChange={(value) => setNodeForm({ ...nodeForm, status: value })} />
              <button className="rounded-xl bg-blue-600 px-4 py-2 text-sm font-semibold text-white disabled:opacity-50" disabled={saving === "node"} onClick={() => submit("node")}>
                {saving === "node" ? "Saving..." : "Save node"}
              </button>
            </div>
          </div>
        </section>
        {loading && <div className="rounded-xl border border-slate-200 bg-white px-4 py-8 text-center text-sm text-slate-500">Loading...</div>}
        {!loading && (
          <>
            <DataTable title="Plans" rows={plans} columns={["id", "plan_code", "plan_name", "plan_type", "price_cents", "status"]} />
            <DataTable title="Pools" rows={pools} columns={["id", "pool_code", "pool_name", "provider", "spec_label", "available_nodes"]} />
            <NodesTable rows={nodes} saving={saving} onStatus={updateNodeStatus} onHealthCheck={healthCheckNode} />
            <DataTable title="Orders" rows={orders} columns={["id", "order_no", "username", "product_type", "amount_cents", "status"]} />
            <DataTable title="Model Call Logs" rows={logs} columns={["id", "user_id", "pool_name", "node_code", "status", "latency_ms", "created_at"]} />
            <DataTable title="Model Feedback" rows={feedback} columns={["id", "username", "pool_name", "node_code", "event_message", "created_at"]} />
          </>
        )}
      </div>
    </AdminLayout>
  )
}
