"use client"

import { memo } from "react"
import { Handle, Position, type NodeProps } from "@xyflow/react"
import { GripVertical } from "lucide-react"
import { cn } from "@/lib/utils"
import { NODE_TYPE_MAP, type NodeTypeDefinition } from "./node-registry"
import type { WorkflowNodeData } from "@/lib/api/types"

export type WorkflowSlot = {
  name: string
  label: string
  type: string
}

type WorkflowFlowNode = {
  data: WorkflowNodeData & {
    nodeDefType?: string
    inputSlots?: WorkflowSlot[]
    outputSlots?: WorkflowSlot[]
    selectedModelLabel?: string
    selectedModelProvider?: string
    selectedModelName?: string
  }
}

function getNodeDef(data: WorkflowFlowNode["data"]): NodeTypeDefinition | undefined {
  if (data.nodeDefType) return NODE_TYPE_MAP.get(data.nodeDefType)
  if (data.kind) return NODE_TYPE_MAP.get(data.kind)
  return undefined
}

function slotTypeColor(type: string): string {
  const colors: Record<string, string> = {
    text: "#2563eb",
    image: "#db2777",
    audio: "#7c3aed",
    video: "#ea580c",
    file: "#0891b2",
    json: "#16a34a",
    any: "#64748b",
  }
  return colors[type] || "#64748b"
}

function slotTypeLabel(type: string): string {
  const labels: Record<string, string> = {
    text: "T",
    image: "I",
    audio: "A",
    video: "V",
    file: "F",
    json: "J",
    any: "*",
  }
  return labels[type] || type.charAt(0).toUpperCase()
}

const HANDLE_SIZE = 9

export const WorkflowNodeComponent = memo(function WorkflowNodeComponent({
  data,
  selected,
}: NodeProps) {
  const nodeData = data as unknown as WorkflowFlowNode["data"]
  const def = getNodeDef(nodeData)
  const color = nodeData.color || def?.color || "#64748b"
  const title = nodeData.title || def?.displayName || "Node"
  const inputSlots = nodeData.inputSlots || def?.inputSlots || []
  const outputSlots = nodeData.outputSlots || def?.outputSlots || []
  const selectedModelLabel = nodeData.selectedModelLabel
  const selectedModelProvider = nodeData.selectedModelProvider
  const selectedModelName = nodeData.selectedModelName

  const headerHeight = 38
  const rowCount = Math.max(inputSlots.length, outputSlots.length, 1)
  const slotTotalHeight = rowCount * 26
  const bodyHeight = Math.max(slotTotalHeight + (selectedModelLabel ? 70 : 42), 84)

  return (
    <div
      className={cn(
        "group relative rounded-lg border bg-card shadow-sm transition-shadow",
        selected
          ? "border-primary shadow-lg ring-2 ring-primary/20"
          : "border-border hover:border-primary/40 hover:shadow-md",
      )}
      style={{ minWidth: def?.defaultWidth || 250 }}
    >
      <div
        className="flex items-center gap-2 rounded-t-[7px] px-3 text-white"
        style={{ backgroundColor: color, height: headerHeight }}
      >
        <span className="truncate text-xs font-semibold">{title}</span>
        <GripVertical className="ml-auto h-3.5 w-3.5 shrink-0 opacity-70" />
      </div>

      <div className="relative px-4 py-2" style={{ minHeight: bodyHeight }}>
        {selectedModelLabel ? (
          <div className="mb-3 rounded-md border border-border/70 bg-secondary/40 px-2 py-1.5 text-left">
            <p className="truncate text-[10px] font-medium text-card-foreground">{selectedModelLabel}</p>
            {selectedModelProvider || selectedModelName ? (
              <p className="mt-0.5 truncate text-[9px] text-muted-foreground">
                {[selectedModelProvider, selectedModelName].filter(Boolean).join(" / ")}
              </p>
            ) : null}
          </div>
        ) : null}

        <div className="grid grid-cols-2 gap-5">
          <div className="space-y-1.5">
            {inputSlots.length === 0 ? (
              <p className="text-[10px] text-muted-foreground">无输入</p>
            ) : (
              inputSlots.map((slot) => (
                <div key={`in-${slot.name}`} className="relative flex items-center gap-1.5 text-[10px]">
                  <Handle
                    type="target"
                    position={Position.Left}
                    id={`in-${slot.name}`}
                    style={{
                      width: HANDLE_SIZE,
                      height: HANDLE_SIZE,
                      left: -20,
                      backgroundColor: slotTypeColor(slot.type),
                      border: `2px solid ${color}`,
                    }}
                    title={`${slot.label} (${slot.type})`}
                  />
                  <span
                    className="flex h-4 w-4 shrink-0 items-center justify-center rounded text-[9px] font-bold text-white"
                    style={{ backgroundColor: slotTypeColor(slot.type) }}
                  >
                    {slotTypeLabel(slot.type)}
                  </span>
                  <span className="min-w-0 truncate text-muted-foreground">{slot.label}</span>
                </div>
              ))
            )}
          </div>

          <div className="space-y-1.5 text-right">
            {outputSlots.length === 0 ? (
              <p className="text-[10px] text-muted-foreground">无输出</p>
            ) : (
              outputSlots.map((slot) => (
                <div key={`out-${slot.name}`} className="relative flex items-center justify-end gap-1.5 text-[10px]">
                  <span className="min-w-0 truncate text-muted-foreground">{slot.label}</span>
                  <span
                    className="flex h-4 w-4 shrink-0 items-center justify-center rounded text-[9px] font-bold text-white"
                    style={{ backgroundColor: slotTypeColor(slot.type) }}
                  >
                    {slotTypeLabel(slot.type)}
                  </span>
                  <Handle
                    type="source"
                    position={Position.Right}
                    id={`out-${slot.name}`}
                    style={{
                      width: HANDLE_SIZE,
                      height: HANDLE_SIZE,
                      right: -20,
                      backgroundColor: slotTypeColor(slot.type),
                      border: `2px solid ${color}`,
                    }}
                    title={`${slot.label} (${slot.type})`}
                  />
                </div>
              ))
            )}
          </div>
        </div>
      </div>
    </div>
  )
})
