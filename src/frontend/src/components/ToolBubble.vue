<script setup>
import { computed } from 'vue'
import { getToolMeta } from '../utils/tools'
import Icon from './Icon.vue'

const props = defineProps({
  event: { type: Object, required: true },
})

const meta = computed(() => getToolMeta(props.event.toolName))
const isRunning = computed(() => !props.event.success && props.event.success !== false)
</script>

<template>
  <div class="tool-bubble" :class="{ running: isRunning, success: event.success === true, fail: event.success === false }">
    <div class="tool-icon" :style="{ background: meta.color + '22', color: meta.color }">
      <Icon v-if="isRunning" name="loader" :size="15" />
      <Icon v-else :name="meta.icon" :size="15" />
    </div>
    <div class="tool-body">
      <div class="tool-head">
        <span class="tool-label">{{ meta.label }}</span>
        <span class="tool-name">{{ event.toolName }}</span>
        <span v-if="event.success === true" class="tool-status ok">
          <Icon name="check" :size="11" /> 成功
        </span>
        <span v-else-if="event.success === false" class="tool-status fail">
          <Icon name="x" :size="11" /> 失败
        </span>
        <span v-else class="tool-status running">
          <Icon name="loader" :size="11" /> 执行中
        </span>
      </div>
      <div v-if="event.summary" class="tool-summary">{{ event.summary }}</div>
    </div>
  </div>
</template>

<style scoped>
.tool-bubble {
  display: flex;
  gap: 10px;
  padding: 10px 12px;
  margin: 6px 0;
  background: var(--bg-surface);
  border: 1px solid var(--border-default);
  border-radius: var(--r-md);
  font-size: 13px;
  transition: all var(--dur-fast);
}
.tool-bubble.running {
  border-color: var(--c-warning);
  background: rgba(245, 158, 11, 0.06);
}
.tool-bubble.success {
  border-color: rgba(16, 185, 129, 0.3);
}
.tool-bubble.fail {
  border-color: rgba(239, 68, 68, 0.3);
  background: rgba(239, 68, 68, 0.05);
}

.tool-icon {
  width: 30px;
  height: 30px;
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.tool-body { flex: 1; min-width: 0; }
.tool-head {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.tool-label {
  font-weight: 600;
  color: var(--t-primary);
}
.tool-name {
  font-family: 'JetBrains Mono', monospace;
  font-size: 11px;
  color: var(--t-tertiary);
  background: rgba(255, 255, 255, 0.04);
  padding: 1px 6px;
  border-radius: 4px;
}
.tool-status {
  margin-left: auto;
  font-size: 11px;
  padding: 2px 8px;
  border-radius: 10px;
  font-weight: 500;
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
.tool-status.ok { color: var(--c-success); background: rgba(16, 185, 129, 0.12); }
.tool-status.fail { color: var(--c-danger); background: rgba(239, 68, 68, 0.12); }
.tool-status.running { color: var(--c-warning); background: rgba(245, 158, 11, 0.12); }

.tool-summary {
  margin-top: 6px;
  font-size: 12px;
  color: var(--t-secondary);
  line-height: 1.5;
  max-height: 80px;
  overflow-y: auto;
  white-space: pre-wrap;
  word-break: break-word;
  font-family: 'JetBrains Mono', monospace;
  background: rgba(0, 0, 0, 0.2);
  padding: 6px 8px;
  border-radius: 6px;
}
</style>
