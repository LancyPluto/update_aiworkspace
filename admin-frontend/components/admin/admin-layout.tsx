"use client"

import { useEffect, useState } from "react"
import { usePathname, useRouter } from "next/navigation"
import { AdminSidebar } from "./sidebar"
import { fetchAdminMe } from "@/lib/api/auth"
import { clearSession } from "@/lib/api/http"

interface AdminLayoutProps {
  children: React.ReactNode
}

export function AdminLayout({ children }: AdminLayoutProps) {
  const router = useRouter()
  const pathname = usePathname()
  const [checking, setChecking] = useState(true)

  useEffect(() => {
    let cancelled = false
    const redirectToLogin = () => {
      const redirect = encodeURIComponent(pathname || "/")
      const loginUrl = `/login?redirect=${redirect}`
      if (typeof window !== "undefined") {
        window.location.replace(loginUrl)
        return
      }
      router.replace(loginUrl)
    }

    async function verifySession() {
      try {
        await fetchAdminMe()
        if (!cancelled) setChecking(false)
      } catch {
        clearSession()
        redirectToLogin()
      }
    }

    verifySession()
    return () => {
      cancelled = true
    }
  }, [pathname, router])

  if (checking) {
    return (
      <div className="flex min-h-screen items-center justify-center bg-background text-sm text-muted-foreground">
        正在校验管理员登录状态...
      </div>
    )
  }

  return (
    <div className="min-h-screen bg-background">
      <AdminSidebar />
      <main className="ml-64">{children}</main>
    </div>
  )
}
