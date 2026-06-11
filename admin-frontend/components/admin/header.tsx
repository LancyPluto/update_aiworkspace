"use client"

import { ThemeToggle } from "@/components/theme-toggle"

interface HeaderProps {
  title: string
  description?: string
  subtitle?: string
}

export function AdminHeader({ title, description, subtitle }: HeaderProps) {
  const helperText = description || subtitle

  return (
    <header className="sticky top-0 z-30 flex h-16 items-center justify-between border-b border-border bg-background/80 px-6 backdrop-blur-xl">
      <div>
        <h1 className="text-xl font-semibold text-foreground">{title}</h1>
        {helperText && (
          <p className="text-sm text-muted-foreground">{helperText}</p>
        )}
      </div>

      <div className="flex items-center gap-3">
        <ThemeToggle />
      </div>
    </header>
  )
}
