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

interface ComparisonSweepPreviewProps {
  originalUrl: string
  effectUrl: string
  title?: string
  subtitle?: string
  badge?: string
  originalAlt?: string
  effectAlt?: string
  className?: string
  mediaClassName?: string
  footerClassName?: string
  showFooter?: boolean
}

function ComparisonPreviewMedia({
  url,
  alt,
  className,
}: {
  url: string
  alt: string
  className?: string
}) {
  if (isVideoPreviewUrl(url)) {
    return (
      <video
        src={url}
        className={className}
        muted
        loop
        autoPlay
        playsInline
        preload="metadata"
        title={alt}
      />
    )
  }

  return <img src={url} alt={alt} className={className} draggable={false} />
}

export function ComparisonSweepPreview({
  originalUrl,
  effectUrl,
  title,
  subtitle,
  badge,
  originalAlt = "原始素材",
  effectAlt = "模型效果",
  className,
  mediaClassName,
  footerClassName,
  showFooter = true,
}: ComparisonSweepPreviewProps) {
  return (
    <div className={cn("tool-comparison-sweep relative overflow-hidden bg-zinc-950 text-white", className)}>
      <ComparisonPreviewMedia
        url={originalUrl}
        alt={originalAlt}
        className={cn("absolute inset-0 h-full w-full object-cover", mediaClassName)}
      />
      <ComparisonPreviewMedia
        url={effectUrl}
        alt={effectAlt}
        className={cn("tool-comparison-sweep__effect absolute inset-0 h-full w-full object-cover", mediaClassName)}
      />
      <div className="pointer-events-none absolute inset-0 z-10 bg-[radial-gradient(circle_at_30%_20%,rgb(255_255_255_/_0.16),transparent_34%),linear-gradient(180deg,transparent_0%,rgb(0_0_0_/_0.14)_52%,rgb(0_0_0_/_0.84)_100%)]" />
      <div className="tool-comparison-sweep__divider pointer-events-none absolute inset-y-0 z-20 -translate-x-1/2">
        <div className="relative h-full w-[2px] bg-white shadow-[0_0_14px_rgb(255_255_255_/_0.85),0_0_34px_rgb(59_130_246_/_0.45)]">
          <span className="absolute left-1/2 top-0 h-full w-5 -translate-x-1/2 bg-gradient-to-r from-transparent via-white/25 to-transparent" />
        </div>
      </div>
      {badge ? (
        <span className="absolute right-3 top-3 z-30 rounded-full bg-black/50 px-2.5 py-1 text-[10px] font-medium text-white/82 ring-1 ring-white/15 backdrop-blur">
          {badge}
        </span>
      ) : null}
      {showFooter ? (
        <div
          className={cn(
            "absolute inset-x-0 bottom-0 z-30 border-t border-white/10 bg-black/62 px-3.5 py-3 backdrop-blur-md",
            footerClassName,
          )}
        >
          <h3 className="truncate text-base font-semibold text-white drop-shadow">{title}</h3>
          {subtitle ? <p className="mt-0.5 line-clamp-1 text-[11px] text-white/72">{subtitle}</p> : null}
        </div>
      ) : null}
    </div>
  )
}

export function ToolUserPreviewCard({ tool, baseUrl, className }: ToolUserPreviewCardProps) {
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
          <ComparisonSweepPreview
            originalUrl={normalizeMediaUrl(tool.comparisonOriginalUrl, baseUrl)}
            effectUrl={normalizeMediaUrl(tool.comparisonEffectUrl, baseUrl)}
            title={tool.name}
            subtitle={description}
            badge={modalityLabel(tool.outputModality)}
            originalAlt={`${tool.name} 原图`}
            effectAlt={`${tool.name} 效果图`}
            className="aspect-[3/4] w-full"
          />
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
