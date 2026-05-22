"use client"

import { Badge } from "@/components/ui/badge"
import { NODE_TYPES, type NodeTypeDefinition } from "./node-registry"

interface NodePaletteProps {
  onDragStart: (event: React.DragEvent, def: NodeTypeDefinition) => void
  onAddNode: (def: NodeTypeDefinition) => void
}

const PINNED_TYPES = ["field_input", "prompt_template", "llm_model", "backend_tool", "final_output"]

export function NodePalette({ onDragStart, onAddNode }: NodePaletteProps) {
  const items = PINNED_TYPES
    .map((type) => NODE_TYPES.find((node) => node.type === type))
    .filter(Boolean) as NodeTypeDefinition[]

  return (
    <aside className="flex h-full flex-col">
      <div className="shrink-0 border-b border-border p-3">
        <p className="text-sm font-semibold text-card-foreground">流程组件</p>
        <p className="mt-1 text-[11px] leading-4 text-muted-foreground">
          只保留输入、提示词、大模型、后端工具和输出五类节点。
        </p>
      </div>

      <div className="flex-1 space-y-2 overflow-y-auto p-2">
        {items.map((def) => {
          const Icon = def.icon
          return (
            <button
              key={def.type}
              type="button"
              draggable
              onDragStart={(event) => onDragStart(event, def)}
              onClick={() => onAddNode(def)}
              className="group flex w-full cursor-grab items-start gap-2 rounded-lg border border-border/70 bg-background px-2.5 py-2 text-left transition hover:border-primary/40 hover:bg-accent active:cursor-grabbing active:scale-[0.99]"
            >
              <span
                className="mt-0.5 flex h-7 w-7 shrink-0 items-center justify-center rounded-md"
                style={{
                  backgroundColor: `${def.color}18`,
                  color: def.color,
                }}
              >
                <Icon className="h-3.5 w-3.5" />
              </span>
              <span className="min-w-0 flex-1">
                <span className="flex items-center gap-1.5">
                  <span className="truncate text-xs font-semibold text-card-foreground">
                    {def.displayName}
                  </span>
                  <Badge variant="secondary" className="shrink-0 text-[10px]">
                    {def.category}
                  </Badge>
                </span>
                <span className="mt-1 line-clamp-2 block text-[11px] leading-4 text-muted-foreground">
                  {def.description}
                </span>
              </span>
            </button>
          )
        })}
      </div>

      <div className="shrink-0 border-t border-border p-3">
        <p className="text-[11px] leading-4 text-muted-foreground">
          点击可快速添加，也可以拖到画布中的指定位置。
        </p>
      </div>
    </aside>
  )
}
