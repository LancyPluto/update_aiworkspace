"use client"

import { useEffect, useMemo, useState } from "react"
import { AdminLayout } from "@/components/admin/admin-layout"
import { AdminHeader } from "@/components/admin/header"
import { DataTable, StatusBadge } from "@/components/admin/data-table"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import {
  annotateAdminCommunityPost,
  featureAdminCommunityPost,
  fetchAdminCommunityPosts,
  fetchAdminCommunityStats,
  hideAdminCommunityPost,
  pinAdminCommunityPost,
  restoreAdminCommunityPost,
} from "@/lib/api/community"
import type { AdminCommunityPost, AdminCommunityStats } from "@/lib/api/types"

function statusTone(status: string) {
  if (status === "PUBLISHED") return "active"
  if (status === "HIDDEN") return "error"
  if (status === "UNPUBLISHED") return "inactive"
  return "pending"
}

function formatTime(value?: string | null) {
  if (!value) return "-"
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString()
}

function splitTags(value: string) {
  return value
    .split(/[,，\s]+/)
    .map((item) => item.trim())
    .filter(Boolean)
    .slice(0, 6)
}

export default function CommunityPostsPage() {
  const [posts, setPosts] = useState<AdminCommunityPost[]>([])
  const [status, setStatus] = useState("")
  const [userId, setUserId] = useState("")
  const [modality, setModality] = useState("")
  const [keyword, setKeyword] = useState("")
  const [featured, setFeatured] = useState("")
  const [auditStatus, setAuditStatus] = useState("")
  const [stats, setStats] = useState<AdminCommunityStats | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [editing, setEditing] = useState<AdminCommunityPost | null>(null)
  const [topicDraft, setTopicDraft] = useState("")
  const [tagsDraft, setTagsDraft] = useState("")
  const [hideReason, setHideReason] = useState("内容不符合社区展示规范")

  async function load() {
    setLoading(true)
    setError(null)
    try {
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
      setStats(await fetchAdminCommunityStats())
    } catch (err) {
      setError(err instanceof Error ? err.message : "加载社区作品失败")
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    void load()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  function startEdit(post: AdminCommunityPost) {
    setEditing(post)
    setTopicDraft(post.topic || "")
    setTagsDraft((post.tags || []).join(", "))
  }

  async function saveAnnotation() {
    if (!editing) return
    await annotateAdminCommunityPost(editing.id, {
      topic: topicDraft.trim() || null,
      tags: splitTags(tagsDraft),
    })
    setEditing(null)
    await load()
  }

  async function toggleVisibility(post: AdminCommunityPost) {
    if (post.status === "HIDDEN") {
      await restoreAdminCommunityPost(post.id)
    } else {
      await hideAdminCommunityPost(post.id, hideReason.trim() || undefined)
    }
    await load()
  }

  async function toggleFeatured(post: AdminCommunityPost) {
    await featureAdminCommunityPost(post.id, !post.featured)
    await load()
  }

  async function togglePinned(post: AdminCommunityPost) {
    await pinAdminCommunityPost(post.id, !post.pinned)
    await load()
  }

  const rows = useMemo(
    () =>
      posts.map((post) => ({
        ...post,
        user: `用户 ${post.userId}`,
        curation: `${post.pinned ? "置顶 " : ""}${post.featured ? "精选" : "普通"}`,
        stats: `${post.viewCount} 浏览 / ${post.likeCount} 赞 / ${post.favoriteCount} 收藏 / ${post.sameStyleCount || 0} 同款`,
        quality: `${post.qualityScore || 0} / ${post.auditStatus || "-"}`,
        tagsText: [post.topic, ...(post.tags || []).map((tag) => `#${tag}`)].filter(Boolean).join(" "),
        time: formatTime(post.createdAt),
      })),
    [posts],
  )

  return (
    <AdminLayout>
      <AdminHeader
        title="社区作品"
        description={error ? `加载失败：${error}` : "管理公开作品的审核、精选、置顶、专题和标签"}
      />

      <div className="space-y-6 p-6">
        {stats && (
          <div className="grid gap-3 md:grid-cols-4">
            {[
              ["作品", stats.postCount],
              ["待审", stats.pendingCount],
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

        <div className="flex flex-wrap items-center gap-3">
          <Input className="w-40" placeholder="用户 ID" value={userId} onChange={(event) => setUserId(event.target.value)} />
          <Input className="w-56" placeholder="搜索标题、工具、描述" value={keyword} onChange={(event) => setKeyword(event.target.value)} />
          <Input className="w-72" placeholder="隐藏原因" value={hideReason} onChange={(event) => setHideReason(event.target.value)} />
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

        {editing && (
          <div className="rounded-xl border border-border bg-card p-4 shadow-sm">
            <div className="mb-3 text-sm font-semibold">编辑专题与标签：{editing.title}</div>
            <div className="flex flex-wrap items-center gap-3">
              <Input className="w-64" placeholder="专题，如 产品图生成" value={topicDraft} onChange={(event) => setTopicDraft(event.target.value)} />
              <Input className="w-96" placeholder="标签，用逗号或空格分隔" value={tagsDraft} onChange={(event) => setTagsDraft(event.target.value)} />
              <Button type="button" onClick={() => void saveAnnotation()}>保存</Button>
              <Button type="button" variant="outline" onClick={() => setEditing(null)}>取消</Button>
            </div>
          </div>
        )}

        <DataTable
          columns={[
            { key: "id", title: "作品 ID" },
            { key: "title", title: "标题" },
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
            { key: "stats", title: "互动" },
            { key: "time", title: "创建时间" },
            {
              key: "actions",
              title: "操作",
              render: (_value, item) => {
                const post = item as AdminCommunityPost
                return (
                  <div className="flex flex-wrap gap-2">
                    <Button variant="outline" size="sm" onClick={() => void toggleVisibility(post)}>
                      {post.status === "HIDDEN" ? "恢复" : "隐藏"}
                    </Button>
                    <Button variant="outline" size="sm" onClick={() => void toggleFeatured(post)}>
                      {post.featured ? "取消精选" : "精选"}
                    </Button>
                    <Button variant="outline" size="sm" onClick={() => void togglePinned(post)}>
                      {post.pinned ? "取消置顶" : "置顶"}
                    </Button>
                    <Button variant="outline" size="sm" onClick={() => startEdit(post)}>
                      标签
                    </Button>
                  </div>
                )
              },
            },
          ]}
          data={rows}
        />
      </div>
    </AdminLayout>
  )
}
