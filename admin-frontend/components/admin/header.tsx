"use client"

import { ThemeToggle } from "@/components/theme-toggle"
import { AdminMobileNavigation } from "@/components/admin/sidebar"

interface HeaderProps {
  title: string
  description?: string
  subtitle?: string
}

export function AdminHeader({ title, description, subtitle }: HeaderProps) {
  const helperText = description || subtitle

  return (
    <header className="sticky top-0 z-30 flex min-h-16 items-center justify-between gap-3 border-b border-border bg-background/80 px-4 py-2 backdrop-blur-xl sm:px-6">
      <div className="flex min-w-0 items-center gap-2">
        <AdminMobileNavigation />
        <div className="min-w-0">
          <h1 className="truncate text-lg font-semibold text-foreground sm:text-xl">{title}</h1>
          {helperText && (
            <p className="truncate text-xs text-muted-foreground sm:text-sm">{helperText}</p>
          )}
        </div>
      </div>

      <div className="flex items-center gap-3">
        <ThemeToggle />
      </div>
    </header>
  )
}
