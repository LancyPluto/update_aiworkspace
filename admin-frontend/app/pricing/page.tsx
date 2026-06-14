"use client"

import { useEffect, useMemo, useState } from "react"
import { AdminLayout } from "@/components/admin/admin-layout"
import { AdminHeader } from "@/components/admin/header"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table"
import { ApiError } from "@/lib/api/http"
import {
  deletePricingMargin,
  deletePricingRule,
  fetchPricingMargins,
  fetchPricingRules,
  savePricingMargin,
  savePricingRule,
  type PricingMargin,
  type PricingRule,
} from "@/lib/api/pricing"

const MARGIN_SCOPE_LABELS: Record<string, string> = {
  GLOBAL: "全平台默认",
  CATEGORY: "按分类",
  MODEL: "按模型配置",
}

const RULE_SCOPE_LABELS: Record<string, string> = {
  MODEL: "按模型配置",
  TOOL: "按工具",
  CATEGORY: "按分类",
}

const RULE_TYPE_LABELS: Record<string, string> = {
  MULTIPLIER: "倍率（乘以系数）",
  TIER: "阶梯倍率",
  ADDITIVE: "附加算力（固定加项）",
}

const MATCH_OP_LABELS: Record<string, string> = {
  ANY: "任意值（参数存在即命中）",
  EQ: "等于",
  VALUE: "按参数数值倍率（如 count=3 → ×3）",
  GT: "大于",
  GTE: "大于等于",
  LT: "小于",
  LTE: "小于等于",
}

const PARAM_KEY_PRESETS = [
  { value: "duration", label: "时长 duration" },
  { value: "quality", label: "清晰度 quality" },
  { value: "resolution", label: "分辨率 resolution" },
  { value: "count", label: "数量 count" },
  { value: "steps", label: "步数 steps" },
  { value: "aspectRatio", label: "比例 aspectRatio" },
]

const emptyRule: PricingRule = {
  scopeType: "MODEL",
  scopeRef: 0,
  paramKey: "",
  ruleType: "MULTIPLIER",
  matchOp: "EQ",
  matchValue: "",
  factor: 1,
  extraCredits: 0,
  priority: 100,
  enabled: true,
  remark: "",
}

function markupPercent(ratio: number): string {
  if (!Number.isFinite(ratio)) return "—"
  const pct = Math.round((ratio - 1) * 100)
  return pct >= 0 ? `+${pct}%` : `${pct}%`
}

export default function PricingConfigPage() {
  const [margins, setMargins] = useState<PricingMargin[]>([])
  const [rules, setRules] = useState<PricingRule[]>([])
  const [marginForm, setMarginForm] = useState<PricingMargin | null>(null)
  const [ruleForm, setRuleForm] = useState<PricingRule>({ ...emptyRule })
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)

  const globalMargin = useMemo(
    () => margins.find((item) => item.scopeType === "GLOBAL" && (item.scopeRef ?? 0) === 0),
    [margins],
  )
  const scopedMargins = useMemo(
    () => margins.filter((item) => item.scopeType !== "GLOBAL" || (item.scopeRef ?? 0) !== 0),
    [margins],
  )

  async function reload() {
    setLoading(true)
    try {
      const [m, r] = await Promise.all([fetchPricingMargins(), fetchPricingRules()])
      setMargins(m)
      setRules(r)
      setError(null)
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "加载失败")
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    reload()
  }, [])

  function resetMarginDraft() {
    setMarginForm({
      scopeType: "CATEGORY",
      scopeRef: 0,
      markupRatio: 1.2,
      minCredits: 0,
      enabled: true,
      remark: "",
    })
  }

  async function submitMargin() {
    if (!marginForm) return
    if (marginForm.scopeType !== "GLOBAL" && !marginForm.scopeRef) {
      setError("请填写分类 ID 或模型配置 ID")
      return
    }
    try {
      await savePricingMargin(marginForm)
      setNotice("利润率配置已保存")
      setError(null)
      if (marginForm.scopeType === "GLOBAL") {
        setMarginForm(null)
      } else {
        resetMarginDraft()
      }
      await reload()
    } catch (err) {
      setNotice(null)
      setError(err instanceof ApiError ? err.message : "保存失败")
    }
  }

  async function submitGlobalMargin() {
    if (!globalMargin) return
    try {
      await savePricingMargin(globalMargin)
      setNotice("全平台默认利润率已更新")
      setError(null)
      await reload()
    } catch (err) {
      setNotice(null)
      setError(err instanceof ApiError ? err.message : "保存失败")
    }
  }

  async function submitRule() {
    if (!ruleForm.scopeRef) {
      setError("请填写作用域 ID（模型配置 ID / 工具 ID / 分类 ID）")
      return
    }
    if (!ruleForm.paramKey.trim()) {
      setError("请填写或选择参数键")
      return
    }
    try {
      await savePricingRule(ruleForm)
      setRuleForm({ ...emptyRule })
      setNotice("定价规则已保存")
      setError(null)
      await reload()
    } catch (err) {
      setNotice(null)
      setError(err instanceof ApiError ? err.message : "保存失败")
    }
  }

  async function removeMargin(id?: number) {
    if (!id) return
    try {
      await deletePricingMargin(id)
      setNotice("已删除")
      setError(null)
      await reload()
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "删除失败")
    }
  }

  async function removeRule(id?: number) {
    if (!id) return
    try {
      await deletePricingRule(id)
      setNotice("已删除")
      setError(null)
      await reload()
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "删除失败")
    }
  }

  return (
    <AdminLayout>
      <AdminHeader
        title="定价配置"
        description="配置平台利润率与参数级定价规则，驱动用户侧算力预估与扣费"
      />
      <div className="space-y-6 p-6">
        {error && <div className="rounded-md bg-destructive/10 px-4 py-2 text-sm text-destructive">{error}</div>}
        {notice && <div className="rounded-md bg-emerald-500/10 px-4 py-2 text-sm text-emerald-700">{notice}</div>}

        <Card>
          <CardHeader>
            <CardTitle>全平台默认利润率</CardTitle>
            <CardDescription>
              所有工具结算时的默认加价倍率。优先级：模型配置 &gt; 分类 &gt; 全平台默认。倍率 1.20 = 在厂商成本上加价 20%。
            </CardDescription>
          </CardHeader>
          <CardContent className="space-y-4">
            {globalMargin ? (
              <div className="grid gap-4 md:grid-cols-4">
                <div className="space-y-2">
                  <Label>加价倍率</Label>
                  <Input
                    type="number"
                    step="0.01"
                    min={0.01}
                    value={globalMargin.markupRatio}
                    onChange={(e) =>
                      setMargins((rows) =>
                        rows.map((row) =>
                          row.id === globalMargin.id
                            ? { ...row, markupRatio: Number(e.target.value) }
                            : row,
                        ),
                      )
                    }
                  />
                  <p className="text-xs text-muted-foreground">当前约 {markupPercent(globalMargin.markupRatio)}</p>
                </div>
                <div className="space-y-2">
                  <Label>保底算力</Label>
                  <Input
                    type="number"
                    min={0}
                    value={globalMargin.minCredits}
                    onChange={(e) =>
                      setMargins((rows) =>
                        rows.map((row) =>
                          row.id === globalMargin.id
                            ? { ...row, minCredits: Number(e.target.value) }
                            : row,
                        ),
                      )
                    }
                  />
                  <p className="text-xs text-muted-foreground">单次最低收取算力，0 表示不保底</p>
                </div>
                <div className="space-y-2">
                  <Label>状态</Label>
                  <Select
                    value={globalMargin.enabled ? "1" : "0"}
                    onValueChange={(v) =>
                      setMargins((rows) =>
                        rows.map((row) =>
                          row.id === globalMargin.id ? { ...row, enabled: v === "1" } : row,
                        ),
                      )
                    }
                  >
                    <SelectTrigger><SelectValue /></SelectTrigger>
                    <SelectContent>
                      <SelectItem value="1">启用</SelectItem>
                      <SelectItem value="0">停用</SelectItem>
                    </SelectContent>
                  </Select>
                </div>
                <div className="space-y-2">
                  <Label>备注</Label>
                  <Input
                    placeholder="例如：默认全局加价 20%"
                    value={globalMargin.remark ?? ""}
                    onChange={(e) =>
                      setMargins((rows) =>
                        rows.map((row) =>
                          row.id === globalMargin.id ? { ...row, remark: e.target.value } : row,
                        ),
                      )
                    }
                  />
                </div>
                <div className="md:col-span-4">
                  <Button onClick={submitGlobalMargin}>保存全平台默认</Button>
                </div>
              </div>
            ) : (
              <p className="text-sm text-muted-foreground">
                {loading ? "加载中…" : "未找到全平台默认配置，请执行 sql/061_pricing_engine.sql 初始化"}
              </p>
            )}
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle>分类 / 模型利润率覆盖</CardTitle>
            <CardDescription>
              为特定分类或模型配置设置独立加价与保底价，覆盖全平台默认值。
            </CardDescription>
          </CardHeader>
          <CardContent className="space-y-4">
            <div className="flex items-center justify-between gap-3">
              <p className="text-sm text-muted-foreground">为单个分类或模型配置设置独立加价，覆盖全平台默认。</p>
              {!marginForm && (
                <Button variant="outline" onClick={resetMarginDraft}>新增覆盖</Button>
              )}
            </div>
            {marginForm && (
            <div className="grid gap-3 md:grid-cols-7">
              <div className="space-y-1">
                <Label>作用域</Label>
                <Select
                  value={marginForm.scopeType}
                  onValueChange={(v) =>
                    setMarginForm({ ...marginForm, scopeType: v, scopeRef: v === "GLOBAL" ? 0 : marginForm.scopeRef ?? 0 })
                  }
                >
                  <SelectTrigger><SelectValue /></SelectTrigger>
                  <SelectContent>
                    <SelectItem value="CATEGORY">按分类</SelectItem>
                    <SelectItem value="MODEL">按模型配置</SelectItem>
                  </SelectContent>
                </Select>
              </div>
              <div className="space-y-1">
                <Label>关联 ID</Label>
                <Input
                  type="number"
                  min={1}
                  placeholder="分类 ID 或模型配置 ID"
                  value={marginForm.scopeRef ?? ""}
                  onChange={(e) => setMarginForm({ ...marginForm, scopeRef: Number(e.target.value) })}
                />
              </div>
              <div className="space-y-1">
                <Label>加价倍率</Label>
                <Input
                  type="number"
                  step="0.01"
                  value={marginForm.markupRatio}
                  onChange={(e) => setMarginForm({ ...marginForm, markupRatio: Number(e.target.value) })}
                />
              </div>
              <div className="space-y-1">
                <Label>保底算力</Label>
                <Input
                  type="number"
                  value={marginForm.minCredits}
                  onChange={(e) => setMarginForm({ ...marginForm, minCredits: Number(e.target.value) })}
                />
              </div>
              <div className="space-y-1">
                <Label>状态</Label>
                <Select
                  value={marginForm.enabled ? "1" : "0"}
                  onValueChange={(v) => setMarginForm({ ...marginForm, enabled: v === "1" })}
                >
                  <SelectTrigger><SelectValue /></SelectTrigger>
                  <SelectContent>
                    <SelectItem value="1">启用</SelectItem>
                    <SelectItem value="0">停用</SelectItem>
                  </SelectContent>
                </Select>
              </div>
              <div className="space-y-1">
                <Label>备注</Label>
                <Input
                  value={marginForm.remark ?? ""}
                  onChange={(e) => setMarginForm({ ...marginForm, remark: e.target.value })}
                />
              </div>
              <div className="flex items-end gap-2">
                <Button onClick={submitMargin}>{marginForm.id ? "更新" : "新增覆盖"}</Button>
                <Button variant="outline" onClick={() => setMarginForm(null)}>取消</Button>
              </div>
            </div>
            )}

            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>作用域</TableHead>
                  <TableHead>关联 ID</TableHead>
                  <TableHead>加价倍率</TableHead>
                  <TableHead>保底</TableHead>
                  <TableHead>状态</TableHead>
                  <TableHead>备注</TableHead>
                  <TableHead>操作</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {scopedMargins.map((m) => (
                  <TableRow key={m.id}>
                    <TableCell>{MARGIN_SCOPE_LABELS[m.scopeType] ?? m.scopeType}</TableCell>
                    <TableCell>{m.scopeRef ?? 0}</TableCell>
                    <TableCell>{m.markupRatio} <span className="text-muted-foreground">({markupPercent(m.markupRatio)})</span></TableCell>
                    <TableCell>{m.minCredits}</TableCell>
                    <TableCell>{m.enabled ? "启用" : "停用"}</TableCell>
                    <TableCell>{m.remark}</TableCell>
                    <TableCell className="space-x-2">
                      <Button variant="outline" size="sm" onClick={() => setMarginForm({ ...m })}>编辑</Button>
                      <Button variant="destructive" size="sm" onClick={() => removeMargin(m.id)}>删除</Button>
                    </TableCell>
                  </TableRow>
                ))}
                {scopedMargins.length === 0 && (
                  <TableRow>
                    <TableCell colSpan={7} className="text-center text-muted-foreground">
                      {loading ? "加载中…" : "暂无分类/模型覆盖，使用全平台默认即可"}
                    </TableCell>
                  </TableRow>
                )}
              </TableBody>
            </Table>
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle>参数定价规则</CardTitle>
            <CardDescription>
              根据任务参数（时长、清晰度、张数等）调整厂商成本。示例：模型 12 下 quality=1080P 倍率 1.5；duration≥10 秒附加 +20 算力。
            </CardDescription>
          </CardHeader>
          <CardContent className="space-y-4">
            <div className="grid gap-3 md:grid-cols-3 lg:grid-cols-6">
              <div className="space-y-1">
                <Label>作用域</Label>
                <Select value={ruleForm.scopeType} onValueChange={(v) => setRuleForm({ ...ruleForm, scopeType: v })}>
                  <SelectTrigger><SelectValue /></SelectTrigger>
                  <SelectContent>
                    {Object.entries(RULE_SCOPE_LABELS).map(([value, label]) => (
                      <SelectItem key={value} value={value}>{label}</SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>
              <div className="space-y-1">
                <Label>关联 ID</Label>
                <Input
                  type="number"
                  min={1}
                  placeholder="模型配置 / 工具 / 分类 ID"
                  value={ruleForm.scopeRef ?? ""}
                  onChange={(e) => setRuleForm({ ...ruleForm, scopeRef: Number(e.target.value) })}
                />
              </div>
              <div className="space-y-1">
                <Label>参数键</Label>
                <Select
                  value={PARAM_KEY_PRESETS.some((p) => p.value === ruleForm.paramKey) ? ruleForm.paramKey : "__custom__"}
                  onValueChange={(v) =>
                    setRuleForm({ ...ruleForm, paramKey: v === "__custom__" ? "" : v })
                  }
                >
                  <SelectTrigger><SelectValue placeholder="选择参数" /></SelectTrigger>
                  <SelectContent>
                    {PARAM_KEY_PRESETS.map((item) => (
                      <SelectItem key={item.value} value={item.value}>{item.label}</SelectItem>
                    ))}
                    <SelectItem value="__custom__">自定义…</SelectItem>
                  </SelectContent>
                </Select>
                {!PARAM_KEY_PRESETS.some((p) => p.value === ruleForm.paramKey) && (
                  <Input
                    className="mt-2"
                    placeholder="自定义参数键"
                    value={ruleForm.paramKey}
                    onChange={(e) => setRuleForm({ ...ruleForm, paramKey: e.target.value })}
                  />
                )}
              </div>
              <div className="space-y-1">
                <Label>规则类型</Label>
                <Select value={ruleForm.ruleType} onValueChange={(v) => setRuleForm({ ...ruleForm, ruleType: v })}>
                  <SelectTrigger><SelectValue /></SelectTrigger>
                  <SelectContent>
                    {Object.entries(RULE_TYPE_LABELS).map(([value, label]) => (
                      <SelectItem key={value} value={value}>{label}</SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>
              <div className="space-y-1">
                <Label>匹配条件</Label>
                <Select value={ruleForm.matchOp} onValueChange={(v) => setRuleForm({ ...ruleForm, matchOp: v })}>
                  <SelectTrigger><SelectValue /></SelectTrigger>
                  <SelectContent>
                    {Object.entries(MATCH_OP_LABELS).map(([value, label]) => (
                      <SelectItem key={value} value={value}>{label}</SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>
              <div className="space-y-1">
                <Label>匹配值</Label>
                <Input
                  placeholder="如 1080P、10"
                  value={ruleForm.matchValue ?? ""}
                  onChange={(e) => setRuleForm({ ...ruleForm, matchValue: e.target.value })}
                />
              </div>
              <div className="space-y-1">
                <Label>{ruleForm.ruleType === "ADDITIVE" ? "附加算力" : "倍率系数"}</Label>
                <Input
                  type="number"
                  step="0.01"
                  value={ruleForm.ruleType === "ADDITIVE" ? ruleForm.extraCredits : ruleForm.factor}
                  onChange={(e) => {
                    const n = Number(e.target.value)
                    if (ruleForm.ruleType === "ADDITIVE") {
                      setRuleForm({ ...ruleForm, extraCredits: n })
                    } else {
                      setRuleForm({ ...ruleForm, factor: n })
                    }
                  }}
                />
              </div>
              <div className="space-y-1">
                <Label>优先级</Label>
                <Input
                  type="number"
                  value={ruleForm.priority}
                  onChange={(e) => setRuleForm({ ...ruleForm, priority: Number(e.target.value) })}
                />
                <p className="text-xs text-muted-foreground">数字越小越先应用</p>
              </div>
              <div className="space-y-1">
                <Label>状态</Label>
                <Select value={ruleForm.enabled ? "1" : "0"} onValueChange={(v) => setRuleForm({ ...ruleForm, enabled: v === "1" })}>
                  <SelectTrigger><SelectValue /></SelectTrigger>
                  <SelectContent>
                    <SelectItem value="1">启用</SelectItem>
                    <SelectItem value="0">停用</SelectItem>
                  </SelectContent>
                </Select>
              </div>
              <div className="space-y-1">
                <Label>备注</Label>
                <Input value={ruleForm.remark ?? ""} onChange={(e) => setRuleForm({ ...ruleForm, remark: e.target.value })} />
              </div>
              <div className="flex items-end gap-2">
                <Button onClick={submitRule}>{ruleForm.id ? "更新规则" : "新增规则"}</Button>
                {ruleForm.id && <Button variant="outline" onClick={() => setRuleForm({ ...emptyRule })}>取消</Button>}
              </div>
            </div>

            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>作用域</TableHead>
                  <TableHead>ID</TableHead>
                  <TableHead>参数</TableHead>
                  <TableHead>类型</TableHead>
                  <TableHead>条件</TableHead>
                  <TableHead>效果</TableHead>
                  <TableHead>优先级</TableHead>
                  <TableHead>状态</TableHead>
                  <TableHead>操作</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {rules.map((r) => (
                  <TableRow key={r.id}>
                    <TableCell>{RULE_SCOPE_LABELS[r.scopeType] ?? r.scopeType}</TableCell>
                    <TableCell>{r.scopeRef ?? 0}</TableCell>
                    <TableCell>{r.paramKey}</TableCell>
                    <TableCell>{RULE_TYPE_LABELS[r.ruleType] ?? r.ruleType}</TableCell>
                    <TableCell>
                      {MATCH_OP_LABELS[r.matchOp] ?? r.matchOp}
                      {r.matchValue ? ` · ${r.matchValue}` : ""}
                    </TableCell>
                    <TableCell>{r.ruleType === "ADDITIVE" ? `+${r.extraCredits} 算力` : `×${r.factor}`}</TableCell>
                    <TableCell>{r.priority}</TableCell>
                    <TableCell>{r.enabled ? "启用" : "停用"}</TableCell>
                    <TableCell className="space-x-2">
                      <Button variant="outline" size="sm" onClick={() => setRuleForm({ ...r })}>编辑</Button>
                      <Button variant="destructive" size="sm" onClick={() => removeRule(r.id)}>删除</Button>
                    </TableCell>
                  </TableRow>
                ))}
                {rules.length === 0 && (
                  <TableRow>
                    <TableCell colSpan={9} className="text-center text-muted-foreground">
                      {loading ? "加载中…" : "暂无规则 — 未配置时仅按模型单价与默认利润率计费"}
                    </TableCell>
                  </TableRow>
                )}
              </TableBody>
            </Table>
          </CardContent>
        </Card>
      </div>
    </AdminLayout>
  )
}
