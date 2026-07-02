import { cn } from "@/lib/utils"
import { CREDIT_POWER_ICON_URL } from "@/lib/public-assets"

type CreditPowerIconProps = {
  className?: string
  size?: number | string
}

export function CreditPowerIcon({ className, size }: CreditPowerIconProps) {
  const style =
    size == null || size === ""
      ? undefined
      : { width: typeof size === "number" ? `${size}px` : size, height: typeof size === "number" ? `${size}px` : size }

  return (
    <img
      src={CREDIT_POWER_ICON_URL}
      alt=""
      aria-hidden
      className={cn("inline-block shrink-0 object-contain", className)}
      style={style}
    />
  )
}
