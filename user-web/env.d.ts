/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_COMMUNITY_MEDIA_OPTIMIZATION?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}

declare module "*.vue" {
  import type { DefineComponent } from "vue"
  const component: DefineComponent<object, object, unknown>
  export default component
}
