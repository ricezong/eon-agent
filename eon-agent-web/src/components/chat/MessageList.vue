<script setup>
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import AppIcon from '@/components/common/AppIcon.vue'
import UserBubble from './UserBubble.vue'
import AssistantBubble from './AssistantBubble.vue'
import { useSessionStore } from '@/stores/session'
import { useSettingsStore } from '@/stores/settings'

const session = useSessionStore()
const settings = useSettingsStore()

const scroller = ref(null)
const stick = ref(true)
let timer = null
let mounted = false

const isLast = (m) => session.messages[session.messages.length - 1]?.id === m.id

function onScroll() {
  const el = scroller.value
  if (!el) return
  stick.value = el.scrollHeight - el.scrollTop - el.clientHeight < 100
}

function toBottom(smooth = true) {
  const el = scroller.value
  if (!el) return
  el.scrollTo({ top: el.scrollHeight, behavior: smooth ? 'smooth' : 'auto' })
  stick.value = true
}

watch(
  () => session.messages.length,
  async () => {
    await nextTick()
    if (stick.value) toBottom(!mounted ? false : true)
    mounted = true
  }
)

watch(
  () => session.streaming,
  (v) => {
    if (v) {
      stick.value = true
      nextTick(() => toBottom(true))
    }
  }
)

// 切换会话：直接落到新会话底部。两个会话消息条数恰好相同时 length watch 不会触发
watch(
  () => session.currentId,
  async () => {
    await nextTick()
    stick.value = true
    toBottom(false)
  }
)

onMounted(() => {
  nextTick(() => toBottom(false))
  // 流式过程中持续吸底（打字机每帧都在变高）
  timer = setInterval(() => {
    if (session.streaming && stick.value && settings.autoScroll) toBottom(false)
  }, 160)
})

onUnmounted(() => clearInterval(timer))

defineExpose({ toBottom })

const loading = computed(() => session.loadingSession)
</script>

<template>
  <div class="list-wrap">
    <div class="list" ref="scroller" @scroll.passive="onScroll">
      <div v-if="loading" class="list__loading">
        <AppIcon name="loader" :size="18" class="spin" />
        <span>正在回放会话账本…</span>
      </div>

      <div v-else class="list__inner">
        <div
          v-for="m in session.messages"
          :key="m.id"
          class="list__item"
          :class="m.role === 'user' ? 'list__item--user' : 'list__item--ai'"
        >
          <UserBubble v-if="m.role === 'user'" :message="m" />
          <AssistantBubble v-else :message="m" :streaming="session.streaming && isLast(m)" />
        </div>
      </div>
    </div>

    <!--
      「回到最新」必须挂在滚动容器之外：它若留在 .list 内（即使 sticky），
      占着 36px 文档流高度，滚到底消失时会让 scrollHeight 骤减、浏览器强制 clamp scrollTop，
      表现为内容突然跳一下。
    -->
    <Transition name="pop">
      <button v-if="!stick" class="to-bottom" title="回到最新" @click="toBottom(true)">
        <AppIcon name="arrowDown" :size="15" />
      </button>
    </Transition>
  </div>
</template>

<style scoped>
.list-wrap {
  position: relative;
  flex: 1;
  min-height: 0;
  display: flex;
}

.list {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  overflow-x: hidden;
  scroll-behavior: auto;
}

.list__inner {
  max-width: 900px;
  margin: 0 auto;
  padding: 18px 20px 8px;
  display: flex;
  flex-direction: column;
  gap: 22px;
}

.list__item {
  animation: fadeUp 0.36s var(--ease) both;
}

.list__loading {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  height: 100%;
  color: var(--text-muted);
  font-size: 13px;
}
.spin {
  animation: spin 1s linear infinite;
}

.to-bottom {
  position: absolute;
  bottom: 14px;
  left: 50%;
  transform: translateX(-50%);
  z-index: 3;
  display: flex;
  width: 36px;
  height: 36px;
  border-radius: 50%;
  border: 1px solid var(--border-strong);
  background: rgba(20, 20, 36, 0.9);
  color: var(--text);
  cursor: pointer;
  place-items: center;
  justify-content: center;
  backdrop-filter: blur(12px);
  box-shadow: var(--shadow-md);
  transition: transform 0.2s var(--ease), background 0.2s;
}
.to-bottom:hover {
  transform: translateX(-50%) translateY(-2px);
  background: rgba(139, 92, 246, 0.28);
}

.pop-enter-from,
.pop-leave-to {
  opacity: 0;
  transform: translateX(-50%) translateY(8px) scale(0.9);
}
.pop-enter-active,
.pop-leave-active {
  transition: all 0.24s var(--ease);
}

@media (max-width: 640px) {
  .list__inner {
    padding: 14px 12px 6px;
    gap: 18px;
  }
}
</style>
