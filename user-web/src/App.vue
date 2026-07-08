<script setup lang="ts">
import { watch } from "vue"
import { RouterView, useRoute, useRouter } from "vue-router"
import ConfirmDeleteDialog from "@/components/ConfirmDeleteDialog/ConfirmDeleteDialog.vue"
import { useAuthStore } from "@/store/authStore"

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()

// mount 早于 /me：失效 token 清掉后需离开需登录页（路由守卫不会在已导航后重跑）
watch(
  () => auth.isLoggedIn,
  (loggedIn) => {
    if (
      !loggedIn &&
      route.meta.requiresAuth !== false &&
      route.name !== "Login"
    ) {
      router.replace({ name: "Login", query: { redirect: route.fullPath } })
    }
  },
)
</script>

<template>
  <RouterView />
  <ConfirmDeleteDialog />
</template>
