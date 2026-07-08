"use client"

import { useMemo, useState } from "react"
import { Music, Sparkles } from "lucide-react"
import { cn } from "@/lib/utils"
import {
  isVideoPreviewUrl,
  mediaDisplayModeLabel,
  modalityLabel,
  normalizeMediaUrl,
  normalizeOutputModality,
  resolveMediaDisplayMode,
  resolveModelBrand,
  resolvePreviewCoverUrl,
  resolvePreviewVariant,
  type ToolPreviewInput,
} from "@/lib/tool-user-preview"

interface ToolUserPreviewCardProps {
  tool: ToolPreviewInput
  baseUrl?: string
  className?: string
}

export function ToolUserPreviewCard({ tool, baseUrl, className }: ToolUserPreviewCardProps) {
  const [comparisonPosition, setComparisonPosition] = useState(50)
  const [coverFailed, setCoverFailed] = useState(false)
  const [brandIconFailed, setBrandIconFailed] = useState(false)

  const variant = resolvePreviewVariant(tool)
  const displayMode = resolveMediaDisplayMode(tool)
  const brand = useMemo(() => resolveModelBrand(tool, baseUrl), [tool, baseUrl])
  const coverUrl = resolvePreviewCoverUrl(tool, baseUrl)
  const audioPreviewUrl = normalizeMediaUrl(tool.audioPreviewUrl, baseUrl)
  const isAudioEffect = normalizeOutputModality(tool.outputModality) === "AUDIO" && Boolean(audioPreviewUrl)
  const displayCover = coverFailed ? "" : coverUrl
  const description = tool.description && tool.description !== "暂无描述" ? tool.description : "点击进入对话"

  function updateComparisonPosition(event: React.MouseEvent<HTMLDivElement>) {
    const rect = event.currentTarget.getBoundingClientRect()
    if (rect.width <= 0) return
    const next = Math.min(92, Math.max(8, ((event.clientX - rect.left) / rect.width) * 100))
    setComparisonPosition(next)
  }

  return (
    <div
      className={cn(
        "relative w-full overflow-hidden rounded-2xl border border-white/8 bg-[#1a1c20] text-white shadow-[0_12px_32px_rgb(0_0_0_/_0.28)]",
        className,
      )}
    >
      <div className="absolute left-3 top-3 z-20 flex flex-wrap items-center gap-1.5">
        <span className="rounded-full bg-black/55 px-2.5 py-1 text-[10px] font-medium text-white/80 backdrop-blur">
          用户端预览
        </span>
        <span className="rounded-full bg-white/10 px-2 py-0.5 text-[10px] text-white/65 backdrop-blur">
          {mediaDisplayModeLabel(displayMode)}
        </span>
      </div>

      {variant === "comparison" ? (
        <div className="flex flex-col">
          <div
            className="relative aspect-[3/4] w-full cursor-ew-resize overflow-hidden bg-muted"
            onMouseMove={updateComparisonPosition}
          >
            <img
              src={normalizeMediaUrl(tool.comparisonOriginalUrl, baseUrl)}
              alt={`${tool.name} 原图`}
              className="absolute inset-0 h-full w-full object-cover"
              draggable={false}
            />
            <img
              src={normalizeMediaUrl(tool.comparisonEffectUrl, baseUrl)}
              alt={`${tool.name} 效果图`}
              className="absolute inset-0 h-full w-full object-cover"
              style={{ clipPath: `inset(0 0 0 ${comparisonPosition}%)` }}
              draggable={false}
            />
            <div
              className="pointer-events-none absolute inset-y-0 z-10 w-px bg-white shadow-[0_0_0_1px_rgb(0_0_0_/_0.35)]"
              style={{ left: `${comparisonPosition}%` }}
            />
            <div
              className="pointer-events-none absolute top-1/2 z-10 flex h-8 w-8 -translate-x-1/2 -translate-y-1/2 items-center justify-center rounded-full border border-white/65 bg-black/45 text-[10px] font-semibold text-white shadow-lg backdrop-blur"
              style={{ left: `${comparisonPosition}%` }}
            >
              ↔
            </div>
            <div className="pointer-events-none absolute inset-0 bg-gradient-to-t from-black/85 via-black/8 to-transparent" />
            <span className="absolute left-3 top-12 rounded-full bg-black/45 px-2 py-0.5 text-[10px] font-medium text-white/85 backdrop-blur">
              原图
            </span>
            <span className="absolute right-3 top-12 rounded-full bg-primary/90 px-2 py-0.5 text-[10px] font-medium text-white backdrop-blur">
              效果
            </span>
            <div className="absolute bottom-3 left-3 right-3 flex items-end justify-between gap-2">
              <div className="min-w-0">
                <h3 className="truncate text-lg font-semibold text-white drop-shadow">{tool.name}</h3>
                <p className="mt-0.5 line-clamp-1 text-[11px] text-white/75">{description}</p>
              </div>
              <span className="shrink-0 rounded-full bg-white/18 px-2 py-0.5 text-[10px] font-medium text-white ring-1 ring-white/25 backdrop-blur">
                {modalityLabel(tool.outputModality)}
              </span>
            </div>
          </div>
        </div>
      ) : variant === "effect" ? (
        <div className="flex flex-col">
          <div className="relative aspect-[3/4] w-full overflow-hidden bg-muted">
            {isAudioEffect ? (
              displayCover ? (
                <img
                  src={displayCover}
                  alt={tool.name}
                  className="h-full w-full object-cover"
                  onError={() => setCoverFailed(true)}
                />
              ) : (
                <div className="flex h-full w-full items-center justify-center bg-gradient-to-br from-white/10 to-primary/20">
                  <Music className="h-12 w-12 text-white/45" />
                </div>
              )
            ) : displayCover && isVideoPreviewUrl(displayCover) ? (
              <video
                src={displayCover}
                className="h-full w-full object-cover"
                muted
                loop
                autoPlay
                playsInline
                preload="metadata"
                onError={() => setCoverFailed(true)}
              />
            ) : displayCover ? (
              <img
                src={displayCover}
                alt={tool.name}
                className="h-full w-full object-cover"
                onError={() => setCoverFailed(true)}
              />
            ) : (
              <div className="flex h-full w-full items-center justify-center bg-gradient-to-br from-white/10 to-primary/20">
                <Sparkles className="h-10 w-10 text-white/40" />
              </div>
            )}
            <div className="pointer-events-none absolute inset-0 bg-gradient-to-t from-black/85 via-black/10 to-transparent" />
            {brand.iconUrl && !brandIconFailed ? (
              <div
                className="absolute left-3 top-12 flex items-center gap-1.5 rounded-full bg-white/90 py-0.5 pl-0.5 pr-2 text-[10px] font-medium text-slate-900 shadow-sm ring-1 ring-white/50 backdrop-blur"
                title={brand.name}
              >
                <img
                  src={brand.iconUrl}
                  alt={brand.name}
                  className="h-4 w-4 rounded-full bg-white object-contain"
                  onError={() => setBrandIconFailed(true)}
                />
                <span className="max-w-[88px] truncate">{brand.name}</span>
              </div>
            ) : null}
            <div className="absolute bottom-3 left-3 right-3 flex items-end justify-between gap-2">
              <div className="min-w-0">
                <h3 className="truncate text-lg font-semibold text-white drop-shadow">{tool.name}</h3>
                <p className="mt-0.5 line-clamp-1 text-[11px] text-white/75">{description}</p>
                {isAudioEffect ? (
                  <audio
                    src={audioPreviewUrl}
                    controls
                    preload="metadata"
                    className="mt-2 h-8 w-full max-w-[220px]"
                  />
                ) : null}
              </div>
              <span className="shrink-0 rounded-full bg-white/18 px-2 py-0.5 text-[10px] font-medium text-white ring-1 ring-white/25 backdrop-blur">
                {modalityLabel(tool.outputModality)}
              </span>
            </div>
          </div>
        </div>
      ) : (
        <div className="flex min-h-[280px] w-full flex-col px-5 pb-5 pt-12">
          <div
            className="mb-5 flex h-[92px] w-[92px] items-center justify-center overflow-hidden rounded-3xl border border-white/10 bg-white p-4 shadow-sm ring-1 ring-white/10"
            style={{ backgroundColor: `${brand.color}14` }}
            title={brand.name}
          >
            {brand.iconUrl && !brandIconFailed ? (
              <img
                src={brand.iconUrl}
                alt={brand.name}
                className="h-full w-full object-contain"
                onError={() => setBrandIconFailed(true)}
              />
            ) : (
              <Sparkles className="h-8 w-8 text-white/50" />
            )}
          </div>
          <h3 className="text-xl font-semibold">{tool.name}</h3>
          <p className="mt-1 max-w-full truncate text-[11px] font-medium text-primary/90">{brand.name}</p>
          <p className="mt-2 line-clamp-3 min-h-[48px] text-sm text-white/55">{description}</p>
          <div className="mt-auto flex items-center justify-between gap-2 pt-6">
            <span className="rounded-full bg-white/8 px-2.5 py-1 text-[11px] text-white/55">
              {modalityLabel(tool.outputModality)}
            </span>
          </div>
        </div>
      )}
    </div>
  )
}
