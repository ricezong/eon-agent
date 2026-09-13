<script setup>
import { useToastStore } from '@/stores/toast'
import AppIcon from './AppIcon.vue'

const toast = useToastStore()

const ICONS = {
  info: 'sparkles',
  success: 'check',
  warn: 'alert',
  error: 'alert'
}
</script>

<template>
  <div class="toast-host">
    <TransitionGroup name="toast">
      <div v-for="t in toast.items" :key="t.id" class="toast glass-strong" :class="`toast--${t.type}`">
        <AppIcon :name="ICONS[t.type] || 'sparkles'" :size="16" />
        <span class="toast__text">{{ t.message }}</span>
        <button class="toast__close" @click="toast.remove(t.id)">
          <AppIcon name="close" :size="13" />
        </button>
      </div>
    </TransitionGroup>
  </div>
</template>

<style scoped>
.toast-host {
  position: fixed;
  z-index: 200;
  left: 50%;
  bottom: 28px;
  transform: translateX(-50%);
  display: flex;
  flex-direction: column;
  gap: 10px;
  align-items: center;
  pointer-events: none;
  width: max-content;
  max-width: min(92vw, 520px);
}

.toast {
  pointer-events: auto;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 12px 10px 14px;
  border-radius: 14px;
  font-size: 13.5px;
  box-shadow: var(--shadow-md);
  max-width: 100%;
}

.toast__text {
  flex: 1;
  min-width: 0;
  word-break: break-word;
}

.toast__close {
  border: none;
  background: transparent;
  color: var(--text-muted);
  cursor: pointer;
  display: flex;
  padding: 2px;
  border-radius: 6px;
  transition: color 0.15s, background 0.15s;
}
.toast__close:hover {
  color: var(--text);
  background: rgba(255, 255, 255, 0.08);
}

.toast--success {
  border-color: rgba(52, 211, 153, 0.32);
  color: #a7f3d0;
}
.toast--error {
  border-color: rgba(251, 113, 133, 0.35);
  color: #fecdd3;
}
.toast--warn {
  border-color: rgba(251, 191, 36, 0.32);
  color: #fde68a;
}
.toast--info {
  color: var(--text-soft);
}

.toast-enter-from,
.toast-leave-to {
  opacity: 0;
  transform: translateY(14px) scale(0.96);
}
.toast-enter-active,
.toast-leave-active {
  transition: all 0.32s var(--ease);
}
.toast-move {
  transition: transform 0.32s var(--ease);
}
</style>
