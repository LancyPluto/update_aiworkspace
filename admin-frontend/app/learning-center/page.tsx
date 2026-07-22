"use client"

import { useEffect, useMemo, useState } from "react"
import { BookOpen, ImagePlus, Loader2, Pencil, Plus, Save, Trash2, Upload, UsersRound } from "lucide-react"
import { AdminLayout } from "@/components/admin/admin-layout"
import { AdminHeader } from "@/components/admin/header"
import { Button } from "@/components/ui/button"
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Switch } from "@/components/ui/switch"
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs"
import { Textarea } from "@/components/ui/textarea"
import {
  createLearningCategory,
  createLearningTutorial,
  deleteLearningCategory,
  deleteLearningTutorial,
  fetchLearningCategories,
  fetchLearningTutorials,
  updateLearningCategory,
  updateLearningTutorial,
  uploadLearningCover,
  type CategoryPayload,
  type LearningCategory,
  type LearningTutorial,
  type TutorialPayload,
} from "@/lib/api/learning-center"
import { ApiError, getBaseUrl } from "@/lib/api/http"
import { fetchSettings, updateSettings, uploadCustomerServiceQr } from "@/lib/api/settings"
import { cn } from "@/lib/utils"

const emptyCategory: CategoryPayload = { name: "", sortOrder: 0, enabled: true }
const emptyTutorial: TutorialPayload = {
  categoryId: 0,
  title: "",
  summary: "",
  coverImageUrl: "",
  videoUrl: "",
  sortOrder: 0,
  enabled: true,
}

function resolveMediaUrl(value?: string) {
  const raw = value?.trim()
  if (!raw || raw.startsWith("http://") || raw.startsWith("https://") || raw.startsWith("data:")) return raw || ""
  const path = raw.startsWith("/") ? raw : `/${raw}`
  const base = getBaseUrl().replace(/\/$/, "")
  return base ? `${base}${path}` : path
}

function errorMessage(error: unknown, fallback: string) {
  return error instanceof ApiError ? error.message : error instanceof Error ? error.message : fallback
}

export default function LearningCenterAdminPage() {
  const [categories, setCategories] = useState<LearningCategory[]>([])
  const [tutorials, setTutorials] = useState<LearningTutorial[]>([])
  const [categoryFilter, setCategoryFilter] = useState(0)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [categoryDialog, setCategoryDialog] = useState(false)
  const [categoryDraft, setCategoryDraft] = useState<CategoryPayload>(emptyCategory)
  const [editingCategory, setEditingCategory] = useState<number | null>(null)
  const [tutorialDialog, setTutorialDialog] = useState(false)
  const [tutorialDraft, setTutorialDraft] = useState<TutorialPayload>(emptyTutorial)
  const [editingTutorial, setEditingTutorial] = useState<number | null>(null)
  const [saving, setSaving] = useState(false)
  const [coverUploading, setCoverUploading] = useState(false)
  const [contactEnabled, setContactEnabled] = useState(true)
  const [contactDescription, setContactDescription] = useState("扫码添加老师，获取课程学习支持")
  const [contactQr, setContactQr] = useState("")
  const [qrUploading, setQrUploading] = useState(false)

  async function load() {
    setLoading(true)
    setError(null)
    try {
      const [categoryData, tutorialData, settings] = await Promise.all([
        fetchLearningCategories(),
        fetchLearningTutorials(),
        fetchSettings(),
      ])
      setCategories(categoryData)
      setTutorials(tutorialData)
      setContactEnabled(settings["customerService.enabled"] !== "false")
      setContactDescription(settings["customerService.description"] || "扫码添加老师，获取课程学习支持")
      setContactQr(settings["customerService.qrCodeUrl"] || "")
    } catch (err) {
      setError(errorMessage(err, "加载学习中心配置失败"))
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => { void load() }, [])

  const visibleTutorials = useMemo(
    () => categoryFilter ? tutorials.filter((item) => item.categoryId === categoryFilter) : tutorials,
    [categoryFilter, tutorials],
  )
  const categoryNames = useMemo(() => new Map(categories.map((item) => [item.id, item.name])), [categories])

  function showNotice(message: string) {
    setNotice(message)
    window.setTimeout(() => setNotice(null), 2200)
  }

  function openCategory(category?: LearningCategory) {
    setEditingCategory(category?.id ?? null)
    setCategoryDraft(category ? { name: category.name, sortOrder: category.sortOrder, enabled: category.enabled } : emptyCategory)
    setCategoryDialog(true)
  }

  async function saveCategory() {
    if (!categoryDraft.name.trim()) return setError("请输入分类名称")
    setSaving(true)
    setError(null)
    try {
      const payload = { ...categoryDraft, name: categoryDraft.name.trim() }
      if (editingCategory) await updateLearningCategory(editingCategory, payload)
      else await createLearningCategory(payload)
      setCategoryDialog(false)
      showNotice(editingCategory ? "分类已更新" : "分类已创建")
      await load()
    } catch (err) {
      setError(errorMessage(err, "保存分类失败"))
    } finally { setSaving(false) }
  }

  async function removeCategory(category: LearningCategory) {
    if (!window.confirm(`确认删除分类“${category.name}”？分类下存在教程时将禁止删除。`)) return
    setError(null)
    try {
      await deleteLearningCategory(category.id)
      if (categoryFilter === category.id) setCategoryFilter(0)
      showNotice("分类已删除")
      await load()
    } catch (err) { setError(errorMessage(err, "删除分类失败")) }
  }

  function openTutorial(tutorial?: LearningTutorial) {
    const defaultCategoryId = categoryFilter || categories[0]?.id || 0
    setEditingTutorial(tutorial?.id ?? null)
    setTutorialDraft(tutorial ? {
      categoryId: tutorial.categoryId,
      title: tutorial.title,
      summary: tutorial.summary,
      coverImageUrl: tutorial.coverImageUrl,
      videoUrl: tutorial.videoUrl,
      sortOrder: tutorial.sortOrder,
      enabled: tutorial.enabled,
    } : { ...emptyTutorial, categoryId: defaultCategoryId })
    setTutorialDialog(true)
  }

  async function saveTutorial() {
    if (!tutorialDraft.categoryId) return setError("请选择教程分类")
    if (!tutorialDraft.title.trim()) return setError("请输入教程标题")
    if (!tutorialDraft.videoUrl.trim()) return setError("请输入视频地址")
    setSaving(true)
    setError(null)
    try {
      const payload = {
        ...tutorialDraft,
        title: tutorialDraft.title.trim(),
        summary: tutorialDraft.summary.trim(),
        coverImageUrl: tutorialDraft.coverImageUrl.trim(),
        videoUrl: tutorialDraft.videoUrl.trim(),
      }
      if (editingTutorial) await updateLearningTutorial(editingTutorial, payload)
      else await createLearningTutorial(payload)
      setTutorialDialog(false)
      showNotice(editingTutorial ? "教程已更新" : "教程已创建")
      await load()
    } catch (err) { setError(errorMessage(err, "保存教程失败")) }
    finally { setSaving(false) }
  }

  async function removeTutorial(tutorial: LearningTutorial) {
    if (!window.confirm(`确认删除教程“${tutorial.title}”？此操作不会删除已上传的封面文件。`)) return
    setError(null)
    try {
      await deleteLearningTutorial(tutorial.id)
      showNotice("教程已删除")
      await load()
    } catch (err) { setError(errorMessage(err, "删除教程失败")) }
  }

  async function uploadCover(file?: File) {
    if (!file) return
    setCoverUploading(true)
    setError(null)
    try {
      const result = await uploadLearningCover(file)
      setTutorialDraft((current) => ({ ...current, coverImageUrl: result.url }))
    } catch (err) { setError(errorMessage(err, "封面上传失败")) }
    finally { setCoverUploading(false) }
  }

  async function uploadTeacherQr(file?: File) {
    if (!file) return
    setQrUploading(true)
    setError(null)
    try {
      const result = await uploadCustomerServiceQr(file)
      setContactQr(result.url)
      showNotice("联系老师二维码已上传")
    } catch (err) { setError(errorMessage(err, "二维码上传失败")) }
    finally { setQrUploading(false) }
  }

  async function saveContact() {
    setSaving(true)
    setError(null)
    try {
      await updateSettings({
        "customerService.enabled": contactEnabled ? "true" : "false",
        "customerService.title": "联系老师",
        "customerService.description": contactDescription.trim(),
        "customerService.qrCodeUrl": contactQr,
      })
      showNotice("联系老师配置已保存")
    } catch (err) { setError(errorMessage(err, "保存联系老师配置失败")) }
    finally { setSaving(false) }
  }

  return (
    <AdminLayout>
      <AdminHeader title="学习中心" description="管理课程阶段、AI 视频教程与老师联系方式" />
      <div className="space-y-5 p-6">
        {error && <div role="alert" className="rounded-md border border-destructive/20 bg-destructive/10 px-4 py-3 text-sm text-destructive">{error}</div>}
        {notice && <div className="rounded-md border border-emerald-500/20 bg-emerald-500/10 px-4 py-3 text-sm text-emerald-600">{notice}</div>}

        <Tabs defaultValue="tutorials">
          <TabsList>
            <TabsTrigger value="tutorials" className="gap-2"><BookOpen className="h-4 w-4" />视频教程</TabsTrigger>
            <TabsTrigger value="categories" className="gap-2">课程分类</TabsTrigger>
            <TabsTrigger value="contact" className="gap-2"><UsersRound className="h-4 w-4" />联系老师</TabsTrigger>
          </TabsList>

          <TabsContent value="tutorials" className="mt-5 space-y-4">
            <div className="flex flex-col gap-3 rounded-lg border border-border bg-card p-4 sm:flex-row sm:items-center sm:justify-between">
              <select className="h-10 rounded-md border border-input bg-background px-3 text-sm" value={categoryFilter} onChange={(event) => setCategoryFilter(Number(event.target.value))}>
                <option value={0}>全部分类</option>
                {categories.map((item) => <option key={item.id} value={item.id}>{item.name}</option>)}
              </select>
              <Button onClick={() => openTutorial()} disabled={!categories.length}><Plus className="mr-2 h-4 w-4" />新增教程</Button>
            </div>
            <div className="overflow-hidden rounded-lg border border-border bg-card">
              <div className="grid grid-cols-[84px_minmax(180px,1.4fr)_minmax(150px,1fr)_80px_76px_92px] gap-3 border-b border-border bg-muted/40 px-4 py-3 text-xs font-medium text-muted-foreground">
                <span>封面</span><span>教程</span><span>分类</span><span>排序</span><span>状态</span><span className="text-right">操作</span>
              </div>
              {loading ? <div className="flex items-center justify-center py-16 text-sm text-muted-foreground"><Loader2 className="mr-2 h-4 w-4 animate-spin" />加载中</div> : visibleTutorials.length ? visibleTutorials.map((item) => (
                <div key={item.id} className="grid grid-cols-[84px_minmax(180px,1.4fr)_minmax(150px,1fr)_80px_76px_92px] items-center gap-3 border-b border-border px-4 py-3 text-sm last:border-b-0">
                  <div className="h-12 w-20 overflow-hidden rounded-md bg-muted">{item.coverImageUrl ? <img src={resolveMediaUrl(item.coverImageUrl)} alt="" className="h-full w-full object-cover" /> : <ImagePlus className="m-auto mt-3 h-5 w-5 text-muted-foreground" />}</div>
                  <div className="min-w-0"><p className="truncate font-medium">{item.title}</p><p className="mt-1 truncate text-xs text-muted-foreground">{item.summary || "暂无简介"}</p></div>
                  <span className="truncate text-muted-foreground">{categoryNames.get(item.categoryId) || `分类 #${item.categoryId}`}</span>
                  <span>{item.sortOrder}</span>
                  <span className={cn("w-fit rounded-full px-2 py-1 text-xs", item.enabled ? "bg-emerald-500/10 text-emerald-600" : "bg-muted text-muted-foreground")}>{item.enabled ? "已上线" : "已下线"}</span>
                  <div className="flex justify-end gap-1"><Button variant="ghost" size="icon" aria-label="编辑教程" onClick={() => openTutorial(item)}><Pencil className="h-4 w-4" /></Button><Button variant="ghost" size="icon" aria-label="删除教程" onClick={() => void removeTutorial(item)}><Trash2 className="h-4 w-4 text-destructive" /></Button></div>
                </div>
              )) : <div className="py-16 text-center text-sm text-muted-foreground">暂无教程，请先创建分类后添加视频教程</div>}
            </div>
          </TabsContent>

          <TabsContent value="categories" className="mt-5 space-y-4">
            <div className="flex justify-end"><Button onClick={() => openCategory()}><Plus className="mr-2 h-4 w-4" />新增分类</Button></div>
            <div className="overflow-hidden rounded-lg border border-border bg-card">
              {categories.length ? categories.map((item) => (
                <div key={item.id} className="flex items-center gap-4 border-b border-border px-5 py-4 last:border-b-0">
                  <div className="min-w-0 flex-1"><p className="font-medium">{item.name}</p><p className="mt-1 text-xs text-muted-foreground">排序 {item.sortOrder} · {tutorials.filter((course) => course.categoryId === item.id).length} 节教程</p></div>
                  <span className={cn("rounded-full px-2 py-1 text-xs", item.enabled ? "bg-emerald-500/10 text-emerald-600" : "bg-muted text-muted-foreground")}>{item.enabled ? "启用" : "停用"}</span>
                  <Button variant="ghost" size="icon" aria-label="编辑分类" onClick={() => openCategory(item)}><Pencil className="h-4 w-4" /></Button>
                  <Button variant="ghost" size="icon" aria-label="删除分类" onClick={() => void removeCategory(item)}><Trash2 className="h-4 w-4 text-destructive" /></Button>
                </div>
              )) : <div className="py-16 text-center text-sm text-muted-foreground">暂无课程分类</div>}
            </div>
          </TabsContent>

          <TabsContent value="contact" className="mt-5">
            <section className="max-w-4xl rounded-lg border border-border bg-card p-5">
              <div className="flex items-center justify-between rounded-md bg-secondary p-4"><div><p className="font-medium">启用“联系老师”</p><p className="text-sm text-muted-foreground">开启后，学习中心标题区展示联系老师按钮。</p></div><Switch checked={contactEnabled} onCheckedChange={setContactEnabled} /></div>
              <div className="mt-5 space-y-2"><Label>弹窗说明</Label><Textarea value={contactDescription} onChange={(event) => setContactDescription(event.target.value)} className="min-h-24" placeholder="扫码添加老师，获取课程学习支持" /></div>
              <div className="mt-5 grid gap-5 md:grid-cols-[minmax(0,1fr)_200px]">
                <label className={cn("flex cursor-pointer flex-col items-center justify-center rounded-lg border border-dashed border-border bg-secondary/30 px-4 py-8 text-center hover:border-primary/60", qrUploading && "pointer-events-none opacity-70")} onDragOver={(event) => event.preventDefault()} onDrop={(event) => { event.preventDefault(); void uploadTeacherQr(event.dataTransfer.files?.[0]) }}>
                  <Upload className="mb-2 h-5 w-5 text-primary" /><span className="text-sm font-medium">{qrUploading ? "上传中..." : "点击选择或拖拽二维码"}</span><span className="mt-1 text-xs text-muted-foreground">JPG、PNG、WebP、GIF，最大 5MB</span>
                  <input type="file" accept="image/jpeg,image/png,image/webp,image/gif" className="hidden" disabled={qrUploading} onChange={(event) => { void uploadTeacherQr(event.target.files?.[0]); event.currentTarget.value = "" }} />
                </label>
                <div className="rounded-lg border border-border bg-secondary/50 p-3"><p className="mb-2 text-sm font-medium">二维码预览</p>{contactQr ? <img src={resolveMediaUrl(contactQr)} alt="联系老师二维码" className="aspect-square w-full rounded-md bg-white object-contain p-2" /> : <div className="flex aspect-square items-center justify-center rounded-md border border-dashed text-xs text-muted-foreground">尚未上传</div>}</div>
              </div>
              <div className="mt-5 flex justify-end"><Button onClick={() => void saveContact()} disabled={saving}><Save className="mr-2 h-4 w-4" />保存联系配置</Button></div>
            </section>
          </TabsContent>
        </Tabs>
      </div>

      <Dialog open={categoryDialog} onOpenChange={setCategoryDialog}>
        <DialogContent><DialogHeader><DialogTitle>{editingCategory ? "编辑分类" : "新增分类"}</DialogTitle><DialogDescription>分类名称可使用“第一阶段-基础课程”等学习阶段描述。</DialogDescription></DialogHeader>
          <div className="space-y-4"><div className="space-y-2"><Label>分类名称</Label><Input value={categoryDraft.name} maxLength={80} onChange={(event) => setCategoryDraft((current) => ({ ...current, name: event.target.value }))} /></div><div className="space-y-2"><Label>排序</Label><Input type="number" min={0} max={9999} value={categoryDraft.sortOrder} onChange={(event) => setCategoryDraft((current) => ({ ...current, sortOrder: Number(event.target.value) }))} /></div><div className="flex items-center justify-between rounded-md bg-secondary p-3"><Label>用户端启用</Label><Switch checked={categoryDraft.enabled} onCheckedChange={(enabled) => setCategoryDraft((current) => ({ ...current, enabled }))} /></div></div>
          <DialogFooter><Button variant="outline" onClick={() => setCategoryDialog(false)}>取消</Button><Button onClick={() => void saveCategory()} disabled={saving}>{saving && <Loader2 className="mr-2 h-4 w-4 animate-spin" />}保存</Button></DialogFooter>
        </DialogContent>
      </Dialog>

      <Dialog open={tutorialDialog} onOpenChange={setTutorialDialog}>
        <DialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-2xl"><DialogHeader><DialogTitle>{editingTutorial ? "编辑视频教程" : "新增视频教程"}</DialogTitle><DialogDescription>上传课程封面并填写浏览器可直接播放的 HTTP/HTTPS 视频地址。</DialogDescription></DialogHeader>
          <div className="grid gap-4 md:grid-cols-2"><div className="space-y-2"><Label>所属分类</Label><select className="h-10 w-full rounded-md border border-input bg-background px-3 text-sm" value={tutorialDraft.categoryId} onChange={(event) => setTutorialDraft((current) => ({ ...current, categoryId: Number(event.target.value) }))}><option value={0}>请选择</option>{categories.map((item) => <option key={item.id} value={item.id}>{item.name}</option>)}</select></div><div className="space-y-2"><Label>排序</Label><Input type="number" min={0} max={9999} value={tutorialDraft.sortOrder} onChange={(event) => setTutorialDraft((current) => ({ ...current, sortOrder: Number(event.target.value) }))} /></div></div>
          <div className="space-y-2"><Label>教程标题</Label><Input value={tutorialDraft.title} maxLength={120} onChange={(event) => setTutorialDraft((current) => ({ ...current, title: event.target.value }))} /></div>
          <div className="space-y-2"><Label>教程简介</Label><Textarea value={tutorialDraft.summary} maxLength={500} onChange={(event) => setTutorialDraft((current) => ({ ...current, summary: event.target.value }))} /></div>
          <div className="space-y-2"><Label>视频地址</Label><Input value={tutorialDraft.videoUrl} placeholder="https://cdn.example.com/course.mp4" onChange={(event) => setTutorialDraft((current) => ({ ...current, videoUrl: event.target.value }))} /></div>
          <div className="space-y-2"><Label>课程封面</Label><label className={cn("flex cursor-pointer items-center justify-center gap-2 rounded-md border border-dashed border-border bg-secondary/30 px-4 py-5 text-sm hover:border-primary/60", coverUploading && "pointer-events-none opacity-70")} onDragOver={(event) => event.preventDefault()} onDrop={(event) => { event.preventDefault(); void uploadCover(event.dataTransfer.files?.[0]) }}><Upload className="h-4 w-4" />{coverUploading ? "上传中..." : "点击或拖拽上传封面（最大 5MB）"}<input className="hidden" type="file" accept="image/jpeg,image/png,image/webp,image/gif" onChange={(event) => { void uploadCover(event.target.files?.[0]); event.currentTarget.value = "" }} /></label>{tutorialDraft.coverImageUrl && <img src={resolveMediaUrl(tutorialDraft.coverImageUrl)} alt="教程封面预览" className="aspect-video w-full rounded-md border border-border object-cover" />}</div>
          <div className="flex items-center justify-between rounded-md bg-secondary p-3"><Label>用户端上线</Label><Switch checked={tutorialDraft.enabled} onCheckedChange={(enabled) => setTutorialDraft((current) => ({ ...current, enabled }))} /></div>
          <DialogFooter><Button variant="outline" onClick={() => setTutorialDialog(false)}>取消</Button><Button onClick={() => void saveTutorial()} disabled={saving || coverUploading}>{saving && <Loader2 className="mr-2 h-4 w-4 animate-spin" />}保存教程</Button></DialogFooter>
        </DialogContent>
      </Dialog>
    </AdminLayout>
  )
}
