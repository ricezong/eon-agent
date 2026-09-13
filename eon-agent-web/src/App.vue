<script setup>
import { onMounted, onUnmounted } from 'vue'
import AuroraBackground from '@/components/common/AuroraBackground.vue'
import AppSidebar from '@/components/layout/AppSidebar.vue'
import ToastHost from '@/components/common/ToastHost.vue'
import { useUiStore } from '@/stores/ui'
import { useSessionStore } from '@/stores/session'

const ui = useUiStore()
const session = useSessionStore()

onMounted(() => {
  ui.syncViewport()
  window.addEventListener('resize', ui.syncViewport)
  session.loadSessions().catch(() => {})
})

onUnmounted(() => {
  window.removeEventListener('resize', ui.syncViewport)
})
</script>

<template>
  <AuroraBackground />

  <div class="shell">
    <AppSidebar class="shell__aside" :class="{ 'is-open': ui.drawerOpen }" />

    <Transition name="fade">
      <div v-if="ui.isMobile && ui.drawerOpen" class="shell__scrim" @click="ui.closeDrawer()" />
    </Transition>

    <main class="shell__main">
      <RouterView v-slot="{ Component }">
        <Transition name="page" mode="out-in">
          <component :is="Component" />
        </Transition>
      </RouterView>
    </main>

    <ToastHost />
  </div>
</template>

<style scoped>
.shell {
  position: relative;
  z-index: 1;
  display: flex;
  height: 100%;
  overflow: hidden;
}

.shell__aside {
  flex: none;
  height: 100%;
}

.shell__main {
  flex: 1;
  min-width: 0;
  height: 100%;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.shell__scrim {
  position: fixed;
  inset: 0;
  z-index: 40;
  background: rgba(4, 4, 10, 0.6);
  backdrop-filter: blur(4px);
}

.page-enter-from,
.page-leave-to {
  opacity: 0;
  transform: translateY(8px);
}
.page-enter-active,
.page-leave-active {
  transition: all 0.28s var(--ease);
}

@media (max-width: 900px) {
  .shell__aside {
    position: fixed;
    top: 0;
    left: 0;
    bottom: 0;
    z-index: 50;
    transform: translateX(-102%);
    transition: transform 0.32s var(--ease);
    box-shadow: none;
  }
  .shell__aside.is-open {
    transform: translateX(0);
    box-shadow: var(--shadow-lg);
  }
}
</style>
