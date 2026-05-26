export interface ToolApiReference {
  title: string
  endpoint: string
  model: string
  provider: string
  baseUrl: string
  fields: string[]
  note: string
  /** 官方文档（可选） */
  docUrl?: string
}

interface ApiReferenceCardProps {
  reference: ToolApiReference
  /** 副标题，默认用于字段配置侧栏 */
  description?: string
}

export function ApiReferenceCard({ reference, description }: ApiReferenceCardProps) {
  return (
    <div className="rounded-xl border border-border bg-card p-5 shadow-sm">
      <div className="mb-4 flex flex-wrap items-start justify-between gap-2">
        <div>
          <h3 className="text-base font-semibold">{reference.title}</h3>
          <p className="mt-1 text-xs text-muted-foreground">
            {description ?? "用于核对字段是否会进入真实模型调用。"}
          </p>
        </div>
        {reference.docUrl ? (
          <a
            href={reference.docUrl}
            target="_blank"
            rel="noreferrer"
            className="shrink-0 text-xs font-medium text-primary underline"
          >
            官方文档
          </a>
        ) : null}
      </div>
      <div className="space-y-2 rounded-lg bg-secondary/40 p-3 text-xs">
        <div className="flex justify-between gap-3">
          <span className="text-muted-foreground">Provider</span>
          <span className="max-w-[55%] text-right font-medium">{reference.provider}</span>
        </div>
        <div className="flex justify-between gap-3">
          <span className="text-muted-foreground">Model</span>
          <span className="max-w-[55%] text-right font-medium">{reference.model}</span>
        </div>
        <div className="flex justify-between gap-3">
          <span className="text-muted-foreground">Endpoint</span>
          <span className="max-w-[55%] text-right font-mono text-[11px]">{reference.endpoint}</span>
        </div>
        <div className="flex justify-between gap-3">
          <span className="text-muted-foreground">Base URL</span>
          <span className="max-w-[55%] truncate text-right font-mono text-[11px]">{reference.baseUrl}</span>
        </div>
      </div>
      <div className="mt-4 space-y-2">
        <p className="text-xs font-medium">说明 / 字段映射</p>
        <div className="space-y-1.5">
          {reference.fields.map((field) => (
            <div
              key={field}
              className="rounded-md border border-border/70 bg-background px-2.5 py-1.5 font-mono text-[11px] text-muted-foreground"
            >
              {field}
            </div>
          ))}
        </div>
      </div>
      <p className="mt-3 text-xs text-muted-foreground">{reference.note}</p>
    </div>
  )
}
