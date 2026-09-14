<script setup>
import { computed } from 'vue'
import AppIcon from '@/components/common/AppIcon.vue'
import { clockTime, copyText } from '@/utils/format'
import { useToastStore } from '@/stores/toast'

const props = defineProps({
  message: { type: Object, required: true }
})

const toast = useToastStore()
const time = computed(() => clockTime(props.message.createdAt))

async function copy() {
  const ok = await copyText(props.message.text)
  toast[ok ? 'success' : 'warn'](ok ? '已复制' : '复制失败')
}
</script>

<template>
  <div class="user">
    <div class="user__body">
      <div class="user__bubble">{{ message.text }}</div>
      <div class="user__meta">
        <span>{{ time }}</span>
        <button class="user__copy" title="复制" @click="copy">
          <AppIcon name="copy" :size="12" />
        </button>
      </div>
    </div>
    <div class="user__avatar">
      <AppIcon name="user" :size="15" />
    </div>
  </div>
</template>

<style scoped>
.user {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  align-items: flex-start;
}

.user__body {
  max-width: min(78%, 760px);
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 4px;
}

.user__bubble {
  padding: 11px 15px;
  border-radius: 18px 18px 6px 18px;
  background: linear-gradient(135deg, rgba(139, 92, 246, 0.9), rgba(79, 70, 229, 0.85));
  color: #fff;
  font-size: 14.2px;
  line-height: 1.72;
  white-space: pre-wrap;
  word-break: break-word;
  box-shadow: 0 10px 26px rgba(99, 102, 241, 0.28);
}

.user__meta {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 11px;
  color: var(--text-muted);
  padding-right: 4px;
}
.user__copy {
  border: none;
  background: transparent;
  color: var(--text-muted);
  cursor: pointer;
  padding: 2px;
  border-radius: 5px;
  display: grid;
  place-items: center;
  opacity: 0;
  transition: opacity 0.18s, color 0.18s;
}
.user:hover .user__copy {
  opacity: 1;
}
.user__copy:hover {
  color: var(--text);
}

.user__avatar {
  width: 32px;
  height: 32px;
  border-radius: 11px;
  background: var(--grad-warm);
  display: grid;
  place-items: center;
  color: #fff;
  flex: none;
  margin-top: 2px;
}

@media (max-width: 640px) {
  .user__body {
    max-width: 86%;
  }
  .user__copy {
    opacity: 1;
  }
}
</style>
