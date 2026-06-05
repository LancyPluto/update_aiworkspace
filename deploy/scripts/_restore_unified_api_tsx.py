#!/usr/bin/env python3
"""Restore unified-api-settings.tsx from git commit with UTF-8 and syntax fixes."""
from __future__ import annotations

import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
TARGET = ROOT / "admin-frontend/components/admin/unified-api-settings.tsx"
COMMIT = "dfdb6619"

text = subprocess.check_output(
    ["git", "show", f"{COMMIT}:admin-frontend/components/admin/unified-api-settings.tsx"],
    cwd=ROOT,
).decode("utf-8")

# Fix broken JSX closing tags where the last char of Chinese text ate '</'
text = re.sub(r"([\u4e00-\u9fff])?/Badge>", r"\1</Badge>", text)
text = re.sub(r"([\u4e00-\u9fff])?/span>", r"\1</span>", text)
text = re.sub(r"([\u4e00-\u9fff])?/div>", r"\1</div>", text)
text = re.sub(r"([\u4e00-\u9fff])?/TableHead>", r"\1</TableHead>", text)
text = re.sub(r"([\u4e00-\u9fff])?/p>", r"\1</p>", text)
text = re.sub(r"([\u4e00-\u9fff])?/CardDescription>", r"\1</CardDescription>", text)
text = re.sub(r"([\u4e00-\u9fff])?/SelectItem>", r"\1</SelectItem>", text)
text = re.sub(r"([\u4e00-\u9fff])?/Label>", r"\1</Label>", text)

# Specific known broken strings from dfdb6619
replacements = {
    "低余?/Badge>": "低余额</Badge>",
    "仅外?/Badge>": "仅外链</Badge>",
    "待手?/Badge>": "待手填</Badge>",
    'return "余额（控制台查看?': 'return "余额（控制台查看）"',
    "return `更新?${": "return `更新于 ${",
    "¥?}/次": "¥—}/次",
    "¥?}/百万": "¥—}/百万",
    "¥?}/次</div>": "¥—}/次</div>",
    "数字?,\n": "数字人\",\n",
    "多模?,\n": "多模态\",\n",
    "低余额? },": "低余额\" },",
    "连通异常? },": "连通异常\" },",
    "模型数优先? },": "模型数优先\" },",
    "个模型?/p>": "个模型</p>",
    "个模型?/span>": "个模型</span>",
    "Agent 可选?/TableHead>": "Agent 可选</TableHead>",
    "模型池?/CardDescription>": "模型池</CardDescription>",
    "仅外链?/SelectItem>": "仅外链</SelectItem>",
    "低余额阈值?/Label>": "低余额阈值</Label>",
    "控制台链接?/Label>": "控制台链接</Label>",
    "余额页链接?/Label>": "余额页链接</Label>",
    "Upstream 模型名?/Label>": "Upstream 模型名</Label>",
    "图标资产名?/Label>": "图标资产名</Label>",
    "—?/span>": "—</span>",
    "—?/div>": "—</div>",
}

for old, new in replacements.items():
    text = text.replace(old, new)

# Re-apply feature additions from latest broken file
if "function isHealthyStatus" not in text:
    insert_after = "function pickPrimaryAccount"
    idx = text.find(insert_after)
    if idx < 0:
        raise SystemExit("pickPrimaryAccount not found")
    end = text.find("\n\nfunction defaultBalanceModeForVendor", idx)
    helpers = '''

function isHealthyStatus(value?: string | null) {
  return (value || "").trim().toUpperCase() === "OK"
}

function modelAccountHealth(model: UnifiedApiModelItem, vendor: UnifiedApiVendorGroup) {
  if (!model.vendorAccountId) return "UNKNOWN"
  const account = vendor.accounts.find((item) => item.id === model.vendorAccountId)
  if (!account) return "UNKNOWN"
  return account.healthStatus || "UNKNOWN"
}

function canEnableAgentForModel(model: UnifiedApiModelItem, vendor: UnifiedApiVendorGroup) {
  if (!model.enabled) return false
  if (!model.vendorAccountId) return true
  return isHealthyStatus(modelAccountHealth(model, vendor))
}

function modelRowTone(model: UnifiedApiModelItem, vendor: UnifiedApiVendorGroup) {
  if (!model.enabled) return ""
  const health = modelAccountHealth(model, vendor)
  if (isHealthyStatus(health)) return "bg-emerald-50/70 hover:bg-emerald-50"
  if (health === "ERROR") return "bg-rose-50/75 hover:bg-rose-50"
  return ""
}

function accountCardTone(account: ModelVendorAccount) {
  if (!account.enabled) return ""
  if (isHealthyStatus(account.healthStatus)) return "border-emerald-200 bg-emerald-50/60"
  if ((account.healthStatus || "").trim().toUpperCase() === "ERROR") return "border-rose-200 bg-rose-50/70"
  return "bg-muted/20"
}
'''
    text = text[:end] + helpers + text[end:]

# Vendor upsert imports
if "upsertModelVendor" not in text:
    text = text.replace(
        'import { fetchModelProviders } from "@/lib/api/model-providers"\nimport { fetchUnifiedApiOverview }',
        'import { fetchModelProviders } from "@/lib/api/model-providers"\nimport { upsertModelVendor } from "@/lib/api/model-vendors"\nimport { fetchUnifiedApiOverview }',
    )
    text = text.replace(
        "  AgentModelConfigPayload,\n  ModelProviderDescriptor,",
        "  AgentModelConfigPayload,\n  ModelVendorPayload,\n  ModelProviderDescriptor,",
    )

# TableRow tone
text = text.replace(
    '<TableRow key={model.id}>',
    '<TableRow key={model.id} className={modelRowTone(model, vendor)}>',
)

# Account card tone
text = text.replace(
    '<div key={account.id} className="rounded-lg border p-3">',
    '<div key={account.id} className={`rounded-lg border p-3 ${accountCardTone(account)}`}>',
)

# toggleModelEnabled - patch if old version
old_toggle = """  const toggleModelEnabled = useCallback(
    async (model: UnifiedApiModelItem, enabled: boolean) => {
      const previous = model.enabled
      patchModelEnabled(model.id, enabled)"""
new_toggle = """  const toggleModelEnabled = useCallback(
    async (model: UnifiedApiModelItem, enabled: boolean) => {
      const previous = model.enabled
      const previousAgentEnabled = model.agentEnabled ?? true
      patchModelEnabled(model.id, enabled)
      if (!enabled) {
        patchModelAgentEnabled(model.id, false)
      }"""
if old_toggle in text and "previousAgentEnabled" not in text:
    text = text.replace(old_toggle, new_toggle)
    text = text.replace(
        "agentEnabled: model.agentEnabled ?? true,",
        "agentEnabled: enabled ? model.agentEnabled ?? true : false,",
        1,
    )
    text = text.replace(
        "        patchModelEnabled(model.id, previous)\n        setError(err instanceof ApiError ? err.message : \"更新失败\")",
        "        patchModelEnabled(model.id, previous)\n        patchModelAgentEnabled(model.id, previousAgentEnabled)\n        setError(err instanceof ApiError ? err.message : \"更新失败\")",
    )
    text = text.replace(
        "    [patchModelEnabled],\n  )\n\n  const toggleModelAgentEnabled",
        "    [patchModelEnabled, patchModelAgentEnabled],\n  )\n\n  const toggleModelAgentEnabled",
    )

# Agent enabled guard
if 'if (agentEnabled && !model.enabled)' not in text:
    text = text.replace(
        """  const toggleModelAgentEnabled = useCallback(
    async (model: UnifiedApiModelItem, agentEnabled: boolean) => {
      const previous = model.agentEnabled ?? true""",
        """  const toggleModelAgentEnabled = useCallback(
    async (model: UnifiedApiModelItem, agentEnabled: boolean) => {
      if (agentEnabled && !model.enabled) {
        setError("请先启用模型，再开启 Agent 可选")
        return
      }
      const previous = model.agentEnabled ?? true""",
    )

# Agent switch disabled + refresh after test - search current broken file for patterns
if "canEnableAgentForModel(model, vendor)" not in text:
    text = text.replace(
        "disabled={togglingAgentModelId === model.id}",
        "disabled={togglingAgentModelId === model.id || !canEnableAgentForModel(model, vendor)}",
    )

TARGET.write_text(text, encoding="utf-8", newline="\n")
print(f"Wrote {TARGET} ({len(text.splitlines())} lines)")
