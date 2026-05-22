"use client"

import { useCallback, useEffect, useRef, useState } from "react"
import { Copy, Maximize2, Trash2 } from "lucide-react"
import { cn } from "@/lib/utils"

export interface ContextMenuAction {
  label: string
  icon?: React.ReactNode
  shortcut?: string
  disabled?: boolean
  destructive?: boolean
  onClick: () => void
}

interface ContextMenuState {
  x: number
  y: number
  actions: ContextMenuAction[]
}

export function useContextMenu() {
  const [menu, setMenu] = useState<ContextMenuState | null>(null)
  const menuRef = useRef<HTMLDivElement>(null)

  const showMenu = useCallback((x: number, y: number, actions: ContextMenuAction[]) => {
    setMenu({ x, y, actions })
  }, [])

  const hideMenu = useCallback(() => {
    setMenu(null)
  }, [])

  useEffect(() => {
    if (!menu) return

    const clickHandler = (event: MouseEvent) => {
      if (menuRef.current && !menuRef.current.contains(event.target as Node)) hideMenu()
    }
    const keyHandler = (event: KeyboardEvent) => {
      if (event.key === "Escape") hideMenu()
    }

    const timer = setTimeout(() => {
      document.addEventListener("click", clickHandler)
      document.addEventListener("contextmenu", clickHandler)
      document.addEventListener("keydown", keyHandler)
    }, 0)

    return () => {
      clearTimeout(timer)
      document.removeEventListener("click", clickHandler)
      document.removeEventListener("contextmenu", clickHandler)
      document.removeEventListener("keydown", keyHandler)
    }
  }, [menu, hideMenu])

  useEffect(() => {
    if (!menu) return
    const element = menuRef.current
    if (!element) return
    const rect = element.getBoundingClientRect()
    const viewportWidth = window.innerWidth
    const viewportHeight = window.innerHeight
    let { x, y } = menu

    if (x + rect.width > viewportWidth) x = viewportWidth - rect.width - 8
    if (y + rect.height > viewportHeight) y = viewportHeight - rect.height - 8
    if (x < 8) x = 8
    if (y < 8) y = 8

    element.style.left = `${x}px`
    element.style.top = `${y}px`
  }, [menu])

  const contextMenu = menu ? (
    <div
      ref={menuRef}
      className="fixed z-[100] min-w-[180px] rounded-lg border border-border bg-card p-1 shadow-xl animate-in fade-in-0 zoom-in-95"
      style={{ left: menu.x, top: menu.y }}
    >
      {menu.actions.map((action, index) => (
        <button
          key={index}
          type="button"
          disabled={action.disabled}
          className={cn(
            "flex w-full items-center gap-2 rounded-md px-2.5 py-1.5 text-left text-sm transition-colors",
            action.destructive
              ? "text-destructive hover:bg-destructive/10"
              : "text-card-foreground hover:bg-accent",
            action.disabled && "cursor-not-allowed opacity-40",
          )}
          onClick={() => {
            action.onClick()
            hideMenu()
          }}
        >
          {action.icon ? (
            <span className="flex h-4 w-4 shrink-0 items-center justify-center">
              {action.icon}
            </span>
          ) : null}
          <span className="flex-1">{action.label}</span>
          {action.shortcut ? (
            <kbd className="ml-4 text-[10px] text-muted-foreground">{action.shortcut}</kbd>
          ) : null}
        </button>
      ))}
    </div>
  ) : null

  return { showMenu, hideMenu, contextMenu }
}

export function buildCanvasActions(
  addNode: (x: number, y: number) => void,
  selectAll: () => void,
): ContextMenuAction[] {
  return [
    {
      label: "添加节点",
      icon: <Copy className="h-4 w-4" />,
      shortcut: "DblClick",
      onClick: () => addNode(0, 0),
    },
    {
      label: "全选",
      icon: <Maximize2 className="h-4 w-4" />,
      shortcut: "Ctrl+A",
      onClick: selectAll,
    },
  ]
}

export function buildNodeActions(
  duplicateNode: () => void,
  deleteNode: () => void,
  canDelete: boolean,
): ContextMenuAction[] {
  return [
    {
      label: "复制节点",
      icon: <Copy className="h-4 w-4" />,
      shortcut: "Ctrl+D",
      onClick: duplicateNode,
    },
    {
      label: "删除节点",
      icon: <Trash2 className="h-4 w-4" />,
      shortcut: "Del",
      destructive: true,
      disabled: !canDelete,
      onClick: deleteNode,
    },
  ]
}
