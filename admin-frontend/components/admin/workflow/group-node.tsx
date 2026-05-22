"use client"

import { useState } from "react"
import { NodeResizer } from "@xyflow/react"
import { Lock, Unlock } from "lucide-react"
import { cn } from "@/lib/utils"
import type { WorkflowGroup } from "@/lib/api/types"

interface GroupNodeProps {
  id: string
  data: {
    title: string
    color?: string
    fontSize?: number
    locked?: boolean
  }
  selected?: boolean
}

function GroupNodeComponent({ id, data, selected }: GroupNodeProps) {
  const [title, setTitle] = useState(data.title)
  const [locked, setLocked] = useState(data.locked ?? false)

  const color = data.color || "#3b82f6"
  const fontSize = data.fontSize || 14

  return (
    <div
      className={cn(
        "group relative h-full w-full rounded-xl border-2 bg-transparent",
        selected
          ? "border-primary/60"
          : "border-border/40 hover:border-primary/30",
      )}
      style={{
        borderColor: selected ? color : `${color}40`,
        borderStyle: "dashed",
      }}
    >
      <NodeResizer
        color={color}
        isVisible={selected}
        minWidth={200}
        minHeight={120}
        handleStyle={{ width: 8, height: 8 }}
        lineStyle={{ borderColor: color, borderWidth: 1 }}
        keepAspectRatio={false}
      />

      {/* Title bar */}
      <div
        className="flex items-center gap-2 rounded-t-xl px-4 py-2 select-none"
        style={{ backgroundColor: `${color}1a` }}
      >
        <input
          value={title}
          onChange={(e) => setTitle(e.target.value)}
          className="min-w-0 flex-1 bg-transparent text-sm font-semibold outline-none"
          style={{ color, fontSize }}
          placeholder="Group name"
          onClick={(e) => e.stopPropagation()}
        />
        <button
          type="button"
          className="shrink-0 rounded p-0.5 opacity-0 transition-opacity group-hover:opacity-100 hover:bg-background/50"
          onClick={(e) => {
            e.stopPropagation()
            setLocked((prev) => !prev)
          }}
          title={locked ? "Unlock group" : "Lock group"}
        >
          {locked ? (
            <Lock className="h-3.5 w-3.5" style={{ color }} />
          ) : (
            <Unlock className="h-3.5 w-3.5" style={{ color }} />
          )}
        </button>
      </div>
    </div>
  )
}

export function createGroupNode(
  group: WorkflowGroup,
): {
  id: string
  type: string
  position: { x: number; y: number }
  data: GroupNodeProps["data"]
  width: number
  height: number
  style: React.CSSProperties
  draggable: boolean
  selectable: boolean
} {
  return {
    id: group.id,
    type: "groupNode",
    position: { x: group.bounding.x, y: group.bounding.y },
    data: {
      title: group.title,
      color: group.color,
      fontSize: group.fontSize,
      locked: group.locked,
    },
    width: group.bounding.width,
    height: group.bounding.height,
    style: { zIndex: -1 },
    draggable: true,
    selectable: true,
  }
}

export { GroupNodeComponent }
