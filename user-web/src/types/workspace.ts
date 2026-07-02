import type { Component } from "vue"

export type WorkspaceNavItem = {
  label: string
  icon?: Component
  to: string
  match?: string[]
}

export type WorkspaceMediaItem = {
  title: string
  subtitle?: string
  image?: string
  to?: string
  tag?: string
}

export type WorkspaceStaticToolItem = {
  title: string
  description: string
  image?: string
  tag: string
  icon: Component
  to?: string
}
