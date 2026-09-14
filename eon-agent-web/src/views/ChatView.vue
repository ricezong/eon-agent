<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import AppHeader from '@/components/layout/AppHeader.vue'
import AppIcon from '@/components/common/AppIcon.vue'
import MessageList from '@/components/chat/MessageList.vue'
import WelcomePanel from '@/components/chat/WelcomePanel.vue'
import TodoPanel from '@/components/chat/TodoPanel.vue'
import ChatComposer from '@/components/chat/ChatComposer.vue'
import { useSessionStore } from '@/stores/session'
import { useUiStore } from '@/stores/ui'
import { formatTokens } from '@/utils/format'

const route = useRoute()
const session = useSessionStore()
const ui = useUiStore()

const composer = ref(null)

const connPill = computed(() => {
  if (session.connection === 'online') return { text: '已连接', cls: 'pill-ok' }
  if (session.connection === 'offline') return { text: '未连接', cls: 'pill-err' }
  return { text: '检测中', cls: '' }
})

async function onSend(text) {
  await session.send(text)
}

async function reload() {
  if (!session.currentId) return
  const id = session.currentId
  await session.openSession(id, true)
  session.loadSessions().catch(() => {})
}

function onPick(text) {
  composer.value?.setText(text)
}

function onKeydown(e) {
  if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'k') {
    e.preventDefault()
    session.newSession()
    composer.value?.focus()
  }
}

onMounted(() => {
  window.addEventListener('keydown', onKeydown)
  const sid = route.query.session
  if (sid && sid !== session.currentId) {
    session.openSession(String(sid)).catch(() => {})
  }
  composer.value?.focus()
})

onUnmounted(() => window.removeEventListener('keydown', onKeydown))
</script>

<template>
  <section class="chat">
    <AppHeader :title="session.title">
      <template #left>
        <button class="btn btn-ghost btn-icon menu-btn" title="会话列表" @click="ui.toggleDrawer()">
          <AppIcon name="menu" :size="18" />
        </button>
      </template>

      <template #actions>
        <span class="pill" :class="connPill.cls" title="后端 /api 连通状态">
          <span class="dot" :class="`dot--${session.connection}`" />
          {{ connPill.text }}
        </span>

        <span v-if="session.lastUsage" class="pill" title="本轮累计 token">
          <AppIcon name="activity" :size="12" />
          {{ formatTokens(session.lastUsage.total) }} tokens
        </span>

        <button class="btn btn-ghost btn-icon" title="重新加载会话" :disabled="!session.currentId" @click="reload">
          <AppIcon name="refresh" :size="16" />
        </button>

        <button class="btn btn-ghost btn-icon" title="新建对话" @click="session.newSession()">
          <AppIcon name="plus" :size="17" />
        </button>
      </template>
    </AppHeader>

    <div class="chat__body">
      <div v-if="!session.hasMessages && !session.loadingSession" class="chat__welcome">
        <WelcomePanel @pick="onPick" />
      </div>
      <MessageList v-else />
    </div>

    <div class="chat__todo">
      <TodoPanel />
    </div>

    <ChatComposer
      ref="composer"
      :streaming="session.streaming"
      :locked="!!session.pendingQuestion"
      :offline="session.connection === 'offline'"
      @send="onSend"
      @stop="session.stop()"
    />
  </section>
</template>

<style scoped>
.chat {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
  flex: 1;
}

.chat__body {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.chat__welcome {
  flex: 1;
  overflow-y: auto;
  display: flex;
  align-items: center;
}

/* 待办面板：消息区与输入框之间，不随消息滚动；面板为空时不占高度 */
.chat__todo {
  flex: none;
  padding: 0 20px;
}
.chat__todo > * {
  max-width: 900px;
  margin: 0 auto 8px;
}

.dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--text-muted);
}
.dot--online {
  background: var(--ok);
  box-shadow: 0 0 0 3px rgba(52, 211, 153, 0.18);
}
.dot--offline {
  background: var(--err);
  box-shadow: 0 0 0 3px rgba(251, 113, 133, 0.16);
}

@media (max-width: 900px) {
  .chat .pill:first-of-type {
    display: none;
  }
}

@media (max-width: 640px) {
  .chat__todo {
    padding: 0 12px;
  }
}
</style>
