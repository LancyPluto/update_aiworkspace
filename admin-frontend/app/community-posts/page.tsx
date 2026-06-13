"use client"

import { useEffect, useMemo, useState } from "react"
import { AdminLayout } from "@/components/admin/admin-layout"
import { AdminHeader } from "@/components/admin/header"
import { DataTable, StatusBadge } from "@/components/admin/data-table"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import {
  annotateAdminCommunityPost,
  approveAdminCommunityPost,
  featureAdminCommunityPost,
  fetchAdminCommunityPosts,
  fetchAdminCommunityReports,
  fetchAdminCommunityStats,
  hideAdminCommunityPost,
  pinAdminCommunityPost,
  rejectAdminCommunityPost,
  resolveAdminCommunityReport,
  restoreAdminCommunityPost,
} from "@/lib/api/community"
import { getBaseUrl } from "@/lib/api/http"
import type { AdminCommunityMetricPoint, AdminCommunityPost, AdminCommunityReport, AdminCommunityStats } from "@/lib/api/types"

type ViewMode = "posts" | "audit" | "reports" | "stats"

const TOPIC_PRESETS = ["产品图生成", "短视频脚本", "小红书文案", "数字人案例"]

function reportTone(status?: string | null) {
  if (status === "REVIEWED") return "active"
  if (status === "DISMISSED") return "inactive"
  return "pending"
}

function statusTone(status: string) {
  if (status === "PUBLISHED") return "active"
  if (status === "HIDDEN") return "error"
  if (status === "UNPUBLISHED") return "inactive"
  return "pending"
}

function auditTone(status?: string | null) {
  if (status === "APPROVED") return "active"
  if (status === "REJECTED") return "error"
  return "pending"
}

function formatTime(value?: string | null) {
  if (!value) return "-"
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString()
}

function splitTags(value: string) {
  return value
    .split(/[,\s，]+/)
    .map((item) => item.trim())
    .filter(Boolean)
    .slice(0, 8)
}

function mediaUrl(value?: string | null) {
  const raw = value?.trim()
  if (!raw) return ""
  if (raw.startsWith("http://") || raw.startsWith("https://") || raw.startsWith("data:")) return raw
  const path = raw.startsWith("/") ? raw : `/${raw}`
  return `${getBaseUrl()}${path}`
}

function postKind(post: AdminCommunityPost) {
  const value = (post.modality || "").toLowerCase()
  if (value.includes("video")) return "video"
  if (value.includes("audio")) return "audio"
  if (value.includes("image")) return "image"
  return "text"
}

function contentText(post: AdminCommunityPost) {
  return post.prompt || post.promptPreview || post.description || "暂无可预览文本"
}

function metricList(title: string, items: AdminCommunityMetricPoint[]) {
  return (
    <div className="rounded-xl border border-border bg-card p-4 shadow-sm">
      <div className="mb-3 text-sm font-semibold">{title}</div>
      <div className="space-y-2">
        {items.length ? (
          items.map((item) => (
            <div key={item.name} className="flex items-center justify-between gap-4 text-sm">
              <span className="truncate text-muted-foreground">{item.name}</span>
              <span className="font-medium">{item.value}</span>
            </div>
          ))
        ) : (
          <div className="text-sm text-muted-foreground">暂无数据</div>
        )}
      </div>
    </div>
  )
}

export default function CommunityPostsPage() {
  const [viewMode, setViewMode] = useState<ViewMode>("posts")
  const [posts, setPosts] = useState<AdminCommunityPost[]>([])
  const [reports, setReports] = useState<AdminCommunityReport[]>([])
  const [reportStatus, setReportStatus] = useState("PENDING")
  const [status, setStatus] = useState("")
  const [userId, setUserId] = useState("")
  const [modality, setModality] = useState("")
  const [keyword, setKeyword] = useState("")
  const [featured, setFeatured] = useState("")
  const [auditStatus, setAuditStatus] = useState("")
  const [stats, setStats] = useState<AdminCommunityStats | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [selected, setSelected] = useState<AdminCommunityPost | null>(null)
  const [selectedReport, setSelectedReport] = useState<AdminCommunityReport | null>(null)
  const [topicDraft, setTopicDraft] = useState("")
  const [tagsDraft, setTagsDraft] = useState("")
  const [hideReason, setHideReason] = useState("内容不符合社区展示规范")

  async function load() {
    setLoading(true)
    setError(null)
    try {
      setStats(await fetchAdminCommunityStats())
      if (viewMode === "reports") {
        const resp = await fetchAdminCommunityReports({
          pageNo: 1,
          pageSize: 50,
          status: reportStatus || undefined,
        })
        setReports(resp.list)
        setSelectedReport((current) => {
          if (!current) return resp.list[0] || null
          return resp.list.find((report) => report.id === current.id) || resp.list[0] || null
        })
        return
      }

      const resp = await fetchAdminCommunityPosts({
        pageNo: 1,
        pageSize: 50,
        status: status || undefined,
        modality: modality || undefined,
        keyword: keyword.trim() || undefined,
        featured: featured ? featured === "true" : undefined,
        auditStatus: auditStatus || undefined,
        userId: userId.trim() ? Number(userId.trim()) : undefined,
      })
      setPosts(resp.list)
      setSelected((current) => {
        if (!current) return resp.list[0] || null
        return resp.list.find((post) => post.id === current.id) || resp.list[0] || null
      })
    } catch (err) {
      setError(err instanceof Error ? err.message : viewMode === "reports" ? "加载举报队列失败" : "加载社区作品失败")
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    void load()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [viewMode])

  function switchView(next: ViewMode) {
    setViewMode(next)
    if (next === "audit") {
      setStatus("")
      setAuditStatus("PENDING")
    }
    if (next === "reports") {
      setReportStatus("PENDING")
    }
  }

  function selectReport(report: AdminCommunityReport) {
    setSelectedReport(report)
  }

  async function resolveReport(report: AdminCommunityReport, nextStatus: "REVIEWED" | "DISMISSED") {
    const updated = await resolveAdminCommunityReport(report.id, {
      status: nextStatus,
      adminNote: hideReason.trim() || undefined,
    })
    setSelectedReport(updated)
    await load()
  }

  async function hideReportedPost(report: AdminCommunityReport) {
    await hideAdminCommunityPost(report.postId, hideReason.trim() || "用户举报内容不当")
    await resolveAdminCommunityReport(report.id, {
      status: "REVIEWED",
      adminNote: hideReason.trim() || "已隐藏被举报作品",
    })
    await load()
  }

  function selectPost(post: AdminCommunityPost) {
    setSelected(post)
    setTopicDraft(post.topic || "")
    setTagsDraft((post.tags || []).join(", "))
  }

  async function saveAnnotation() {
    if (!selected) return
    const updated = await annotateAdminCommunityPost(selected.id, {
      topic: topicDraft.trim() || null,
      tags: splitTags(tagsDraft),
    })
    setSelected(updated)
    await load()
  }

  async function toggleVisibility(post: AdminCommunityPost) {
    const updated =
      post.status === "HIDDEN"
        ? await restoreAdminCommunityPost(post.id)
        : await hideAdminCommunityPost(post.id, hideReason.trim() || undefined)
    setSelected(updated)
    await load()
  }

  async function approvePost(post: AdminCommunityPost) {
    const updated = await approveAdminCommunityPost(post.id)
    setSelected(updated)
    await load()
  }

  async function rejectPost(post: AdminCommunityPost) {
    const updated = await rejectAdminCommunityPost(post.id, hideReason.trim() || undefined)
    setSelected(updated)
    await load()
  }

  async function toggleFeatured(post: AdminCommunityPost) {
    const updated = await featureAdminCommunityPost(post.id, !post.featured)
    setSelected(updated)
    await load()
  }

  async function togglePinned(post: AdminCommunityPost) {
    const updated = await pinAdminCommunityPost(post.id, !post.pinned)
    setSelected(updated)
    await load()
  }

  const reportRows = useMemo(
    () =>
      reports.map((report) => ({
        ...report,
        reporter: `用户 ${report.reporterUserId}`,
        postLabel: report.postTitle || `作品 #${report.postId}`,
        reasonText: report.reason || "未填写原因",
        time: formatTime(report.createdAt),
      })),
    [reports],
  )

  const rows = useMemo(
    () =>
      posts.map((post) => ({
        ...post,
        user: `用户 ${post.userId}`,
        curation: `${post.pinned ? "置顶 " : ""}${post.featured ? "精选" : "普通"}`,
        stats: `${post.viewCount} 浏览 / ${post.likeCount} 赞 / ${post.favoriteCount} 收藏 / ${post.sameStyleCount || 0} 同款`,
        quality: `${post.qualityScore || 0}`,
        tagsText: [post.topic, ...(post.tags || []).map((tag) => `#${tag}`)].filter(Boolean).join(" "),
        time: formatTime(post.createdAt),
      })),
    [posts],
  )

  return (
    <AdminLayout>
      <AdminHeader
        title="社区运营"
        description={error ? `加载失败：${error}` : "审核作品内容，并在同一面板完成专题、标签、推荐和置顶"}
      />

      <div className="space-y-6 p-6">
        <div className="flex flex-wrap gap-2">
          {[
            ["posts", "作品管理"],
            ["audit", "审核队列"],
            ["reports", "举报队列"],
            ["stats", "数据看板"],
          ].map(([value, label]) => (
            <Button
              key={value}
              type="button"
              variant={viewMode === value ? "default" : "outline"}
              onClick={() => switchView(value as ViewMode)}
            >
              {label}
            </Button>
          ))}
        </div>

        {stats && (
          <div className="grid gap-3 md:grid-cols-4">
            {[
              ["作品", stats.postCount],
              ["待审", stats.pendingCount],
              ["待处理举报", stats.reportPendingCount ?? 0],
              ["隐藏", stats.hiddenCount],
              ["详情访问", stats.detailViewCount],
              ["同款点击", stats.sameStyleClickCount],
              ["任务创建", stats.taskCreatedCount],
              ["曝光", stats.impressionCount],
              ["积分消费", stats.creditSpent],
            ].map(([label, value]) => (
              <div key={label} className="rounded-xl border border-border bg-card p-4 shadow-sm">
                <div className="text-xs text-muted-foreground">{label}</div>
                <div className="mt-1 text-2xl font-semibold">{value}</div>
              </div>
            ))}
          </div>
        )}

        {viewMode === "stats" && stats ? (
          <div className="grid gap-4 lg:grid-cols-3">
            {metricList("热门工具", stats.topTools)}
            {metricList("热门专题", stats.topTopics)}
            {metricList("创作者贡献", stats.topCreators)}
          </div>
        ) : viewMode === "reports" ? (
          <>
            <div className="flex flex-wrap items-center gap-3">
              <Input className="w-72" placeholder="处理备注 / 隐藏原因" value={hideReason} onChange={(event) => setHideReason(event.target.value)} />
              <select className="h-10 rounded-md border border-input bg-background px-3 text-sm" value={reportStatus} onChange={(event) => setReportStatus(event.target.value)}>
                <option value="PENDING">待处理</option>
                <option value="REVIEWED">已处理</option>
                <option value="DISMISSED">已忽略</option>
                <option value="">全部</option>
              </select>
              <Button type="button" onClick={() => void load()} disabled={loading}>
                {loading ? "加载中..." : "筛选"}
              </Button>
            </div>

            <div className="grid gap-6 xl:grid-cols-[minmax(0,1fr)_420px]">
              <DataTable
                columns={[
                  {
                    key: "preview",
                    title: "被举报作品",
                    render: (_value, item) => {
                      const report = item as AdminCommunityReport
                      const url = mediaUrl(report.postCoverUrl)
                      return (
                        <button type="button" className="flex max-w-[360px] items-center gap-3 text-left" onClick={() => selectReport(report)}>
                          <div className="flex h-16 w-20 shrink-0 items-center justify-center overflow-hidden rounded-md border bg-muted text-xs text-muted-foreground">
                            {url ? (
                              // eslint-disable-next-line @next/next/no-img-element
                              <img src={url} alt={report.postTitle || `作品 ${report.postId}`} className="h-full w-full object-cover" />
                            ) : (
                              `#${report.postId}`
                            )}
                          </div>
                          <div className="min-w-0">
                            <div className="truncate font-medium">{report.postTitle || `作品 #${report.postId}`}</div>
                            <div className="mt-1 text-xs text-muted-foreground">作品状态：{report.postStatus || "-"}</div>
                          </div>
                        </button>
                      )
                    },
                  },
                  { key: "reporter", title: "举报人" },
                  { key: "reasonText", title: "原因" },
                  {
                    key: "status",
                    title: "状态",
                    render: (_value, item) => {
                      const report = item as AdminCommunityReport
                      return <StatusBadge status={reportTone(report.status)} label={report.status} />
                    },
                  },
                  { key: "time", title: "举报时间" },
                ]}
                data={reportRows}
              />

              <aside className="rounded-xl border border-border bg-card p-4 shadow-sm">
                {selectedReport ? (
                  <div className="space-y-4">
                    <div>
                      <div className="text-xs text-muted-foreground">举报 #{selectedReport.id}</div>
                      <h2 className="mt-1 text-lg font-semibold">{selectedReport.postTitle || `作品 #${selectedReport.postId}`}</h2>
                      <div className="mt-2 flex flex-wrap gap-2">
                        <StatusBadge status={reportTone(selectedReport.status)} label={selectedReport.status} />
                        <StatusBadge status={statusTone(selectedReport.postStatus || "PUBLISHED")} label={selectedReport.postStatus || "-"} />
                      </div>
                    </div>

                    <div className="overflow-hidden rounded-lg border bg-muted/30">
                      {mediaUrl(selectedReport.postCoverUrl) ? (
                        // eslint-disable-next-line @next/next/no-img-element
                        <img src={mediaUrl(selectedReport.postCoverUrl)} alt={selectedReport.postTitle || `作品 ${selectedReport.postId}`} className="max-h-80 w-full object-contain" />
                      ) : (
                        <div className="flex min-h-40 items-center justify-center text-sm text-muted-foreground">暂无封面</div>
                      )}
                    </div>

                    <div className="grid gap-2 text-sm">
                      <div>
                        <span className="text-muted-foreground">举报人：</span>
                        <span>用户 {selectedReport.reporterUserId}</span>
                      </div>
                      <div>
                        <span className="text-muted-foreground">举报时间：</span>
                        <span>{formatTime(selectedReport.createdAt)}</span>
                      </div>
                      <div>
                        <span className="text-muted-foreground">处理时间：</span>
                        <span>{formatTime(selectedReport.reviewedAt)}</span>
                      </div>
                    </div>

                    <div className="rounded-lg border bg-background p-3">
                      <div className="mb-2 text-xs font-medium text-muted-foreground">举报原因</div>
                      <p className="whitespace-pre-wrap text-sm">{selectedReport.reason || "未填写原因"}</p>
                    </div>

                    {selectedReport.adminNote && (
                      <div className="rounded-lg border bg-background p-3">
                        <div className="mb-2 text-xs font-medium text-muted-foreground">处理备注</div>
                        <p className="whitespace-pre-wrap text-sm">{selectedReport.adminNote}</p>
                      </div>
                    )}

                    <div className="flex flex-wrap gap-2">
                      {selectedReport.status === "PENDING" && (
                        <>
                          <Button type="button" variant="outline" onClick={() => void resolveReport(selectedReport, "DISMISSED")}>
                            忽略
                          </Button>
                          <Button type="button" variant="outline" onClick={() => void resolveReport(selectedReport, "REVIEWED")}>
                            标记已处理
                          </Button>
                          <Button type="button" onClick={() => void hideReportedPost(selectedReport)}>
                            隐藏作品
                          </Button>
                        </>
                      )}
                    </div>
                  </div>
                ) : (
                  <div className="flex min-h-80 items-center justify-center text-sm text-muted-foreground">
                    选择左侧举报记录后查看详情
                  </div>
                )}
              </aside>
            </div>
          </>
        ) : (
          <>
            <div className="flex flex-wrap items-center gap-3">
              <Input className="w-40" placeholder="用户 ID" value={userId} onChange={(event) => setUserId(event.target.value)} />
              <Input className="w-56" placeholder="搜索标题、工具、描述、Prompt" value={keyword} onChange={(event) => setKeyword(event.target.value)} />
              <Input className="w-72" placeholder="隐藏或驳回原因" value={hideReason} onChange={(event) => setHideReason(event.target.value)} />
              <select className="h-10 rounded-md border border-input bg-background px-3 text-sm" value={status} onChange={(event) => setStatus(event.target.value)}>
                <option value="">全部状态</option>
                <option value="PUBLISHED">PUBLISHED</option>
                <option value="HIDDEN">HIDDEN</option>
                <option value="UNPUBLISHED">UNPUBLISHED</option>
              </select>
              <select className="h-10 rounded-md border border-input bg-background px-3 text-sm" value={modality} onChange={(event) => setModality(event.target.value)}>
                <option value="">全部模态</option>
                <option value="IMAGE">IMAGE</option>
                <option value="VIDEO">VIDEO</option>
                <option value="TEXT">TEXT</option>
                <option value="AUDIO">AUDIO</option>
              </select>
              <select className="h-10 rounded-md border border-input bg-background px-3 text-sm" value={featured} onChange={(event) => setFeatured(event.target.value)}>
                <option value="">全部推荐</option>
                <option value="true">只看精选</option>
                <option value="false">非精选</option>
              </select>
              <select className="h-10 rounded-md border border-input bg-background px-3 text-sm" value={auditStatus} onChange={(event) => setAuditStatus(event.target.value)}>
                <option value="">全部审核</option>
                <option value="PENDING">PENDING</option>
                <option value="APPROVED">APPROVED</option>
                <option value="REJECTED">REJECTED</option>
              </select>
              <Button type="button" onClick={() => void load()} disabled={loading}>
                {loading ? "加载中..." : "筛选"}
              </Button>
            </div>

            <div className="grid gap-6 xl:grid-cols-[minmax(0,1fr)_420px]">
              <DataTable
                columns={[
                  {
                    key: "preview",
                    title: "内容",
                    render: (_value, item) => {
                      const post = item as AdminCommunityPost
                      const url = mediaUrl(post.coverUrl)
                      return (
                        <button type="button" className="flex max-w-[360px] items-center gap-3 text-left" onClick={() => selectPost(post)}>
                          <div className="flex h-16 w-20 shrink-0 items-center justify-center overflow-hidden rounded-md border bg-muted text-xs text-muted-foreground">
                            {postKind(post) === "image" && url ? (
                              // eslint-disable-next-line @next/next/no-img-element
                              <img src={url} alt={post.title} className="h-full w-full object-cover" />
                            ) : postKind(post) === "video" && url ? (
                              <video src={url} className="h-full w-full object-cover" muted preload="metadata" />
                            ) : (
                              post.modality || "TEXT"
                            )}
                          </div>
                          <div className="min-w-0">
                            <div className="truncate font-medium">{post.title}</div>
                            <div className="mt-1 line-clamp-2 text-xs text-muted-foreground">{post.description || post.promptPreview || post.toolName || "暂无描述"}</div>
                          </div>
                        </button>
                      )
                    },
                  },
                  { key: "user", title: "用户" },
                  { key: "modality", title: "模态" },
                  { key: "curation", title: "运营位" },
                  { key: "tagsText", title: "专题/标签" },
                  {
                    key: "status",
                    title: "状态",
                    render: (_value, item) => {
                      const post = item as AdminCommunityPost
                      return <StatusBadge status={statusTone(post.status)} label={post.status} />
                    },
                  },
                  {
                    key: "auditStatus",
                    title: "审核",
                    render: (_value, item) => {
                      const post = item as AdminCommunityPost
                      return <StatusBadge status={auditTone(post.auditStatus)} label={post.auditStatus || "-"} />
                    },
                  },
                  { key: "quality", title: "质量分" },
                  { key: "stats", title: "互动" },
                ]}
                data={rows}
              />

              <aside className="rounded-xl border border-border bg-card p-4 shadow-sm">
                {selected ? (
                  <div className="space-y-4">
                    <div>
                      <div className="text-xs text-muted-foreground">作品 #{selected.id}</div>
                      <h2 className="mt-1 text-lg font-semibold">{selected.title}</h2>
                      <div className="mt-2 flex flex-wrap gap-2">
                        <StatusBadge status={statusTone(selected.status)} label={selected.status} />
                        <StatusBadge status={auditTone(selected.auditStatus)} label={selected.auditStatus || "-"} />
                        {selected.promptVisible ? <span className="rounded-full bg-accent/10 px-2 py-1 text-xs text-accent">Prompt 公开</span> : <span className="rounded-full bg-muted px-2 py-1 text-xs text-muted-foreground">Prompt 不公开</span>}
                      </div>
                    </div>

                    <div className="overflow-hidden rounded-lg border bg-muted/30">
                      {postKind(selected) === "image" && mediaUrl(selected.coverUrl) ? (
                        // eslint-disable-next-line @next/next/no-img-element
                        <img src={mediaUrl(selected.coverUrl)} alt={selected.title} className="max-h-80 w-full object-contain" />
                      ) : postKind(selected) === "video" && mediaUrl(selected.coverUrl) ? (
                        <video src={mediaUrl(selected.coverUrl)} className="max-h-80 w-full bg-black" controls preload="metadata" />
                      ) : postKind(selected) === "audio" && mediaUrl(selected.coverUrl) ? (
                        <div className="p-4">
                          <audio src={mediaUrl(selected.coverUrl)} className="w-full" controls preload="metadata" />
                        </div>
                      ) : (
                        <pre className="max-h-80 whitespace-pre-wrap p-4 text-sm text-muted-foreground">{contentText(selected)}</pre>
                      )}
                    </div>

                    <div className="grid gap-2 text-sm">
                      <div>
                        <span className="text-muted-foreground">工具：</span>
                        <span>{selected.toolName || selected.toolCode || "-"}</span>
                      </div>
                      <div>
                        <span className="text-muted-foreground">作者：</span>
                        <span>{selected.authorNickname || `用户 ${selected.userId}`}</span>
                      </div>
                      {selected.auditReason && (
                        <div>
                          <span className="text-muted-foreground">原因：</span>
                          <span>{selected.auditReason}</span>
                        </div>
                      )}
                    </div>

                    <div className="rounded-lg border bg-background p-3">
                      <div className="mb-2 text-xs font-medium text-muted-foreground">描述</div>
                      <p className="whitespace-pre-wrap text-sm">{selected.description || "暂无描述"}</p>
                    </div>

                    <div className="rounded-lg border bg-background p-3">
                      <div className="mb-2 text-xs font-medium text-muted-foreground">Prompt 快照</div>
                      <pre className="max-h-48 whitespace-pre-wrap text-sm">{contentText(selected)}</pre>
                    </div>

                    <div className="space-y-3 rounded-lg border bg-background p-3">
                      <div className="text-sm font-medium">专题与标签</div>
                      <div className="flex flex-wrap gap-2">
                        {TOPIC_PRESETS.map((topic) => (
                          <Button key={topic} type="button" size="sm" variant={topicDraft === topic ? "default" : "outline"} onClick={() => setTopicDraft(topic)}>
                            {topic}
                          </Button>
                        ))}
                      </div>
                      <Input placeholder="专题，如 产品图生成" value={topicDraft} onChange={(event) => setTopicDraft(event.target.value)} />
                      <Input placeholder="标签，用逗号或空格分隔" value={tagsDraft} onChange={(event) => setTagsDraft(event.target.value)} />
                      <Button type="button" onClick={() => void saveAnnotation()}>
                        保存打标
                      </Button>
                    </div>

                    <div className="flex flex-wrap gap-2">
                      {selected.auditStatus !== "APPROVED" && (
                        <Button type="button" onClick={() => void approvePost(selected)}>
                          通过
                        </Button>
                      )}
                      {selected.auditStatus !== "REJECTED" && (
                        <Button type="button" variant="outline" onClick={() => void rejectPost(selected)}>
                          驳回
                        </Button>
                      )}
                      <Button type="button" variant="outline" onClick={() => void toggleVisibility(selected)}>
                        {selected.status === "HIDDEN" ? "恢复" : "隐藏"}
                      </Button>
                      <Button type="button" variant="outline" onClick={() => void toggleFeatured(selected)}>
                        {selected.featured ? "取消精选" : "精选"}
                      </Button>
                      <Button type="button" variant="outline" onClick={() => void togglePinned(selected)}>
                        {selected.pinned ? "取消置顶" : "置顶"}
                      </Button>
                    </div>
                  </div>
                ) : (
                  <div className="flex min-h-80 items-center justify-center text-sm text-muted-foreground">
                    选择左侧作品后查看内容并打标
                  </div>
                )}
              </aside>
            </div>
          </>
        )}
      </div>
    </AdminLayout>
  )
}
