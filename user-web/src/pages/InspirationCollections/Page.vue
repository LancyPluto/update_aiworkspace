<script setup lang="ts">
import AppShell from "@/components/AppShell.vue"
import InspirationLibrary from "@/components/inspiration/InspirationLibrary.vue"
</script>

<template>
  <AppShell title="灵感收藏夹" description="收藏公开社区作品，作为可复用的个人灵感库">
    <div class="flex min-h-0 flex-1">
      <InspirationLibrary class="min-w-0 flex-1" />
    </div>
  </AppShell>
</template>
