"use client"

import { useCallback, useRef, useState } from "react"
import type { WorkflowNode, WorkflowEdge, WorkflowGroup } from "@/lib/api/types"

interface WorkflowSnapshot {
  nodes: WorkflowNode[]
  edges: WorkflowEdge[]
  groups: WorkflowGroup[]
}

interface UseUndoRedoReturn {
  pushSnapshot: (nodes: WorkflowNode[], edges: WorkflowEdge[], groups?: WorkflowGroup[]) => void
  undo: () => WorkflowSnapshot | null
  redo: () => WorkflowSnapshot | null
  canUndo: boolean
  canRedo: boolean
  clear: () => void
}

const MAX_HISTORY = 50

export function useUndoRedo(): UseUndoRedoReturn {
  const undoStack = useRef<WorkflowSnapshot[]>([])
  const redoStack = useRef<WorkflowSnapshot[]>([])
  const [canUndo, setCanUndo] = useState(false)
  const [canRedo, setCanRedo] = useState(false)

  const pushSnapshot = useCallback(
    (nodes: WorkflowNode[], edges: WorkflowEdge[], groups: WorkflowGroup[] = []) => {
      undoStack.current.push({
        nodes: JSON.parse(JSON.stringify(nodes)),
        edges: JSON.parse(JSON.stringify(edges)),
        groups: JSON.parse(JSON.stringify(groups)),
      })
      if (undoStack.current.length > MAX_HISTORY) {
        undoStack.current.shift()
      }
      redoStack.current = []
      setCanUndo(true)
      setCanRedo(false)
    },
    [],
  )

  const undo = useCallback(() => {
    const snapshot = undoStack.current.pop()
    if (!snapshot) return null
    redoStack.current.push(snapshot)
    setCanUndo(undoStack.current.length > 0)
    setCanRedo(true)
    return undoStack.current.length > 0
      ? undoStack.current[undoStack.current.length - 1]
      : null
  }, [])

  const redo = useCallback(() => {
    const snapshot = redoStack.current.pop()
    if (!snapshot) return null
    undoStack.current.push(snapshot)
    setCanUndo(true)
    setCanRedo(redoStack.current.length > 0)
    return snapshot
  }, [])

  const clear = useCallback(() => {
    undoStack.current = []
    redoStack.current = []
    setCanUndo(false)
    setCanRedo(false)
  }, [])

  return { pushSnapshot, undo, redo, canUndo, canRedo, clear }
}
