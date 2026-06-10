import {
  Bot,
  Box,
  Clapperboard,
  Compass,
  Home,
  ImageIcon,
  Lightbulb,
  Sparkles,
  Ticket,
  UserRound,
  Video,
  Wallet,
  Wand2,
} from "lucide-vue-next"
import type { WorkspaceMediaItem, WorkspaceNavItem, WorkspaceStaticToolItem } from "@/types/workspace"

export const workspaceMedia = {
  video: "",
  image: "",
  transform: "",
  effects: "",
  marketing: "",
  avatar: "",
  lip: "",
  editor: "",
  style: "",
  agent: "",
  voice: "",
  product: "",
}

export const workspaceNavGroups: { label?: string; items: WorkspaceNavItem[] }[] = [
  {
    items: [
      { label: "首页", icon: Home, to: "/home", match: ["/home"] },
      { label: "生成", icon: Sparkles, to: "/create", match: ["/create", "/dashboard"] },
    ],
  },
  {
    label: "创意",
    items: [
      { label: "视频", icon: Video, to: "/video" },
      { label: "图片", icon: ImageIcon, to: "/image" },
      { label: "智能体", icon: Bot, to: "/agent", match: ["/agent", "/agents"] },
    ],
  },
  {
    items: [
      { label: "工具", icon: Box, to: "/tool", match: ["/tool", "/tools", "/marketplace", "/chat"] },
      { label: "资产", icon: Ticket, to: "/assets", match: ["/assets", "/library"] },
      { label: "PPT", icon: Clapperboard, to: "/tools/banana_ppt_generator/workspace" },
      { label: "社区", icon: Compass, to: "/community", match: ["/community/posts"] },
      { label: "灵感收藏", icon: Lightbulb, to: "/community/inspirations" },
      { label: "智能体", icon: Bot, to: "/agents", match: ["/agent", "/agents"] },
    ],
  },
]

export const workspaceBottomNav: WorkspaceNavItem[] = [
  { label: "会员与算力", icon: Wallet, to: "/billing", match: ["/billing", "/pricing"] },
]

export const workspaceShowcaseTools: WorkspaceStaticToolItem[] = [
  { title: "视频升级器", description: "提升分辨率、细节和清晰度。", image: workspaceMedia.style, tag: "画质", icon: Video, to: "/tool" },
  { title: "视频增强器", description: "修复低光、噪点和模糊画面。", image: workspaceMedia.video, tag: "增强", icon: Sparkles, to: "/tool" },
  { title: "图像生成视频", description: "上传图片并生成镜头运动。", image: workspaceMedia.image, tag: "图生视频", icon: Clapperboard, to: "/video" },
  { title: "AI 图像生成器", description: "生成海报、商品图和角色图。", image: workspaceMedia.image, tag: "图片", icon: ImageIcon, to: "/image" },
  { title: "智能体工作流", description: "串联脚本、素材、模型和导出。", image: workspaceMedia.agent, tag: "智能体", icon: Bot, to: "/agent" },
  { title: "营销素材生成", description: "面向投放和商品场景的创意工具。", image: workspaceMedia.product, tag: "营销", icon: Sparkles, to: "/tool" },
  { title: "数字人口播", description: "照片驱动口播和多语言音色。", image: workspaceMedia.avatar, tag: "数字人", icon: UserRound, to: "/agent" },
  { title: "图片编辑器", description: "重绘、擦除、换背景和风格转换。", image: workspaceMedia.editor, tag: "编辑", icon: Wand2, to: "/image" },
]

export const workspaceTemplates: WorkspaceMediaItem[] = [
  { title: "新品上市短片", subtitle: "电商", image: workspaceMedia.marketing, to: "/video", tag: "营销" },
  { title: "节日营销视频", subtitle: "热门", image: workspaceMedia.effects, to: "/video", tag: "模板" },
  { title: "品牌故事开场", subtitle: "品牌", image: workspaceMedia.video, to: "/video", tag: "视频" },
  { title: "数字人口播封面", subtitle: "口播", image: workspaceMedia.avatar, to: "/agent", tag: "数字人" },
  { title: "产品图转短片", subtitle: "商品", image: workspaceMedia.product, to: "/video", tag: "图生视频" },
]
