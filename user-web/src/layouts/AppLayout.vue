<script setup lang="ts">
import { RouterView } from "vue-router"
import AppShell from "@/components/AppShell.vue"

const keepAliveIncludes = ["MaterialLibraryPage", "CommunityDiscoverPage"]
</script>

<template>
  <AppShell>
    <RouterView v-slot="{ Component }">
      <KeepAlive :include="keepAliveIncludes">
        <component :is="Component" />
      </KeepAlive>
    </RouterView>
  </AppShell>
</template>
