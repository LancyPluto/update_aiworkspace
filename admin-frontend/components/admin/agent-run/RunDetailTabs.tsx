"use client"

import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs"
import { ContextSnapshotPanel } from "@/components/admin/agent-run/ContextSnapshotPanel"
import { MemoryHitPanel } from "@/components/admin/agent-run/MemoryHitPanel"
import { ModelConversationPanel } from "@/components/admin/agent-run/ModelConversationPanel"
import { RouterRulesPanel } from "@/components/admin/agent-run/RouterRulesPanel"
import { RunTimeline } from "@/components/admin/agent-run/RunTimeline"
import { TechnicalDetails } from "@/components/admin/agent-run/TechnicalDetails"
import { ToolCallCard } from "@/components/admin/agent-run/ToolCallCard"
import type { AdminAgentRunDetail } from "@/lib/api/types"
import type { RunDiagnosis } from "@/lib/agent-run-diagnostics"

export function RunDetailTabs({
  detail,
  diagnosis,
}: {
  detail: AdminAgentRunDetail
  diagnosis: RunDiagnosis
}) {
  return (
    <Tabs defaultValue="timeline" className="w-full">
      <TabsList className="flex h-auto flex-wrap gap-1">
        <TabsTrigger value="timeline">时间线</TabsTrigger>
        <TabsTrigger value="router">路由规则</TabsTrigger>
        <TabsTrigger value="tools">工具</TabsTrigger>
        <TabsTrigger value="memory">记忆</TabsTrigger>
        <TabsTrigger value="conversation">对话</TabsTrigger>
        <TabsTrigger value="context">上下文</TabsTrigger>
      </TabsList>
      <TabsContent value="timeline" className="mt-4">
        <RunTimeline steps={diagnosis.timeline} />
      </TabsContent>
      <TabsContent value="router" className="mt-4">
        <RouterRulesPanel events={detail.events} />
      </TabsContent>
      <TabsContent value="tools" className="mt-4 space-y-3">
        {detail.toolCalls.length === 0 ? (
          <p className="text-sm text-muted-foreground">暂无工具调用记录</p>
        ) : (
          detail.toolCalls.map((call) => <ToolCallCard key={call.id} call={call} />)
        )}
      </TabsContent>
      <TabsContent value="memory" className="mt-4">
        <MemoryHitPanel events={detail.events} run={detail.run} />
      </TabsContent>
      <TabsContent value="conversation" className="mt-4">
        <ModelConversationPanel events={detail.events} />
      </TabsContent>
      <TabsContent value="context" className="mt-4">
        <ContextSnapshotPanel snapshot={detail.contextSnapshot} toolCalls={detail.toolCalls} />
      </TabsContent>
      <div className="mt-6">
        <TechnicalDetails events={detail.events} toolCalls={detail.toolCalls} />
      </div>
    </Tabs>
  )
}
