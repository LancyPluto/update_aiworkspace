"use client"

import { Badge } from "@/components/ui/badge"
import type { AdminAgentRunContextSnapshot } from "@/lib/api/types"
import { parseAgentFileRefs } from "@/lib/agent-run-utils"
import type { AgentToolCall } from "@/lib/api/types"

export function ContextSnapshotPanel({
  snapshot,
  toolCalls,
}: {
  snapshot?: AdminAgentRunContextSnapshot | null
  toolCalls: AgentToolCall[]
}) {
  if (!snapshot) {
    return <p className="text-sm text-muted-foreground">未加载上下文快照</p>
  }

  const badRefs = toolCalls.flatMap((call) => {
    try {
      return parseAgentFileRefs(JSON.parse(call.argumentsJson || "{}"))
    } catch {
      return []
    }
  })
  const availableIds = new Set(snapshot.agentFiles.map((f) => f.id))

  return (
    <div className="space-y-4 text-sm">
      <div className="rounded-lg border p-4">
        <p className="font-medium">用户消息</p>
        <p className="mt-2 whitespace-pre-wrap text-muted-foreground">{snapshot.userMessage || "—"}</p>
        <p className="mt-2 text-xs text-muted-foreground">
          session #{snapshot.sessionId} · workspace #{snapshot.workspaceId} · user #{snapshot.userId}
        </p>
      </div>

      <div className="rounded-lg border p-4">
        <p className="mb-3 font-medium">会话附件（{snapshot.agentFiles.length}）</p>
        {snapshot.agentFiles.length === 0 ? (
          <p className="text-muted-foreground">无附件记录</p>
        ) : (
          <div className="grid gap-2">
            {snapshot.agentFiles.map((file) => (
              <div key={file.id} className="rounded border bg-muted/20 p-2">
                <div className="flex flex-wrap items-center gap-2">
                  <Badge variant="outline">#{file.id}</Badge>
                  <Badge variant={file.status === "READY" ? "secondary" : "outline"}>{file.status}</Badge>
                  <span className="font-medium">{file.originalFilename || "未命名"}</span>
                </div>
                <p className="mt-1 break-all text-xs text-muted-foreground">{file.downloadUrl}</p>
              </div>
            ))}
          </div>
        )}
      </div>

      {badRefs.length > 0 ? (
        <div className="rounded-lg border border-destructive/30 bg-destructive/5 p-4">
          <p className="font-medium text-destructive">参数中引用了可能失效的附件</p>
          <ul className="mt-2 space-y-1 text-muted-foreground">
            {badRefs.map((ref) => {
              const missing = !availableIds.has(ref.fileId)
              return (
                <li key={`${ref.sessionId}-${ref.fileId}`}>
                  session#{ref.sessionId} file#{ref.fileId}
                  {missing ? (
                    <Badge className="ml-2" variant="destructive">
                      不在当前附件列表
                    </Badge>
                  ) : (
                    <Badge className="ml-2" variant="secondary">
                      存在
                    </Badge>
                  )}
                </li>
              )
            })}
          </ul>
        </div>
      ) : null}
    </div>
  )
}
