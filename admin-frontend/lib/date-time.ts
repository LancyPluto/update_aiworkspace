const SHANGHAI_TIME_ZONE = "Asia/Shanghai"
const TIME_ZONE_SUFFIX = /(?:z|[+-]\d{2}:?\d{2})$/i
const DATE_ONLY = /^\d{4}-\d{2}-\d{2}$/

const SHANGHAI_DATE_TIME_FORMATTER = new Intl.DateTimeFormat("en-CA", {
  timeZone: SHANGHAI_TIME_ZONE,
  year: "numeric",
  month: "2-digit",
  day: "2-digit",
  hour: "2-digit",
  minute: "2-digit",
  second: "2-digit",
  hourCycle: "h23",
})

export function formatShanghaiDateTime(value?: string | null): string {
  if (!value) return "-"
  const trimmed = value.trim()
  const isoValue = DATE_ONLY.test(trimmed)
    ? `${trimmed}T00:00:00+08:00`
    : trimmed.replace(" ", "T")
  const date = new Date(TIME_ZONE_SUFFIX.test(isoValue) ? isoValue : `${isoValue}+08:00`)
  if (Number.isNaN(date.getTime())) return value

  const parts = Object.fromEntries(
    SHANGHAI_DATE_TIME_FORMATTER.formatToParts(date)
      .filter((part) => part.type !== "literal")
      .map((part) => [part.type, part.value]),
  )
  return `${parts.year}-${parts.month}-${parts.day} ${parts.hour}:${parts.minute}:${parts.second}`
}
