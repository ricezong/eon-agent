<script setup>
import { computed, ref } from 'vue'
import AppIcon from '@/components/common/AppIcon.vue'
import { useSessionStore } from '@/stores/session'

/**
 * 待办面板。挂在消息区与输入框之间，随 session.todo 事件全量刷新；
 * 只读——清单由 Agent 通过 todo_write 维护，手动改动会与后端 TodoStore 不同步。
 */
const session = useSessionStore()

const open = ref(true)

const items = computed(() => session.todos)

const doneCount = computed(
  () => items.value.filter((t) => t.status === 'COMPLETED' || t.status === 'CANCELLED').length
)

const running = computed(() => items.value.some((t) => t.status === 'IN_PROGRESS'))
</script>

<template>
  <div v-if="items.length" class="tp">
    <header class="tp__head">
      <button class="tp__fold" :title="open ? '收起' : '展开'" @click="open = !open">
        <AppIcon :name="open ? 'chevronDown' : 'chevronRight'" :size="14" />
      </button>

      <AppIcon name="listChecks" :size="14" class="tp__lead" />

      <span class="tp__title">任务清单</span>

      <span class="tp__progress">
        <span class="tp__bar">
          <i :style="{ width: Math.round((doneCount / items.length) * 100) + '%' }" />
        </span>
        <span class="tp__count">{{ doneCount }}/{{ items.length }}</span>
      </span>

      <span v-if="running" class="tp__live" />
    </header>

    <ul v-if="open" class="tp__list">
      <li v-for="t in items" :key="t.id" class="tp__item" :class="'is-' + String(t.status).toLowerCase()">
        <span class="tp__dot">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4"
               stroke-linecap="round" stroke-linejoin="round">
            <circle v-if="t.status === 'PENDING'" cx="12" cy="12" r="8" />
            <circle v-else-if="t.status === 'IN_PROGRESS'" cx="12" cy="12" r="8" stroke-dasharray="38 12" />
            <path v-else-if="t.status === 'COMPLETED'" d="m5 13 4 4L19 7" />
            <path v-else-if="t.status === 'CANCELLED'" d="M18 6 6 18M6 6l12 12" />
            <path v-else d="M10.3 3.9 1.8 18a2 2 0 0 0 1.7 3h17a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0z" />
          </svg>
        </span>

        <span class="tp__text">{{ t.content }}</span>

        <span v-if="t.blockReason" class="tp__reason">{{ t.blockReason }}</span>
      </li>
    </ul>
  </div>
</template>

<style scoped>
.tp {
  border: 1px solid var(--border);
  border-radius: var(--r-md);
  background: rgba(255, 255, 255, 0.035);
  overflow: hidden;
}

.tp__head {
  display: flex;
  align-items: center;
  gap: 7px;
  padding: 7px 10px;
}
.tp__fold {
  border: none;
  background: transparent;
  color: var(--text-muted);
  cursor: pointer;
  padding: 2px;
  display: grid;
  place-items: center;
  border-radius: 5px;
}
.tp__fold:hover {
  color: var(--text);
  background: rgba(255, 255, 255, 0.07);
}
.tp__lead {
  color: #a5b4fc;
}
.tp__title {
  font-size: 12.5px;
  font-weight: 500;
  flex: none;
}
.tp__progress {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 7px;
  flex: none;
}
.tp__bar {
  width: 76px;
  height: 4px;
  border-radius: 99px;
  background: rgba(255, 255, 255, 0.1);
  overflow: hidden;
}
.tp__bar i {
  display: block;
  height: 100%;
  border-radius: 99px;
  background: var(--grad-brand);
  transition: width 0.3s var(--ease);
}
.tp__count {
  font-size: 11px;
  color: var(--text-muted);
  font-variant-numeric: tabular-nums;
}
.tp__live {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #a78bfa;
  animation: tp-pulse 1.4s ease-in-out infinite;
  flex: none;
}

.tp__list {
  list-style: none;
  margin: 0;
  padding: 0 10px 9px;
  max-height: 168px;
  overflow: auto;
  display: flex;
  flex-direction: column;
  gap: 5px;
}
.tp__item {
  display: flex;
  align-items: flex-start;
  gap: 7px;
  font-size: 12.5px;
  line-height: 1.5;
  color: var(--text-soft);
}
.tp__dot {
  width: 13px;
  height: 13px;
  flex: none;
  margin-top: 3px;
  color: var(--text-muted);
}
.tp__dot svg {
  width: 100%;
  height: 100%;
  display: block;
}
.tp__text {
  min-width: 0;
  word-break: break-word;
}
.tp__reason {
  flex: none;
  font-size: 10.5px;
  color: #fda4af;
}

.is-in_progress .tp__dot {
  color: #a78bfa;
}
.is-in_progress .tp__dot svg {
  animation: tp-spin 1.1s linear infinite;
}
.is-in_progress .tp__text {
  color: var(--text);
}
.is-completed .tp__dot {
  color: #6ee7b7;
}
.is-completed .tp__text {
  color: var(--text-muted);
  text-decoration: line-through;
}
.is-cancelled .tp__dot {
  color: var(--text-muted);
}
.is-cancelled .tp__text {
  color: var(--text-muted);
  text-decoration: line-through;
}
.is-blocked .tp__dot {
  color: #fda4af;
}
.is-blocked .tp__text {
  color: #fda4af;
}

@keyframes tp-spin {
  to {
    transform: rotate(360deg);
  }
}
@keyframes tp-pulse {
  0%,
  100% {
    opacity: 0.35;
  }
  50% {
    opacity: 1;
  }
}
</style>
