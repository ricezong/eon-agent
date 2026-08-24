<script setup>
import { computed } from 'vue'
import Icon from './Icon.vue'

const props = defineProps({
  thought: { type: String, default: '' },
  toolNames: { type: Array, default: () => [] },
  turn: { type: Number, default: 0 },
})

const hasThought = computed(() => props.thought && props.thought.trim())
</script>

<template>
  <div class="thought-block">
    <div class="thought-header">
      <Icon name="sparkles" :size="14" />
      <span class="thought-label">思考</span>
      <span v-if="turn" class="thought-turn">Turn {{ turn }}</span>
    </div>
    <div v-if="hasThought" class="thought-text">{{ thought }}</div>
    <div v-if="toolNames && toolNames.length" class="thought-tools">
      <span class="tools-label">计划调用：</span>
      <span v-for="t in toolNames" :key="t" class="tool-chip">{{ t }}</span>
    </div>
  </div>
</template>

<style scoped>
.thought-block {
  margin: 6px 0;
  padding: 10px 12px;
  background: var(--grad-primary-soft);
  border: 1px solid var(--border-primary);
  border-radius: var(--r-md);
  font-size: 13px;
}
.thought-header {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 6px;
  color: var(--c-primary-light);
}
.thought-label {
  font-size: 11px;
  font-weight: 600;
  color: var(--c-primary-light);
  text-transform: uppercase;
  letter-spacing: 1px;
}
.thought-turn {
  margin-left: auto;
  font-size: 10px;
  color: var(--t-tertiary);
  font-family: 'JetBrains Mono', monospace;
}
.thought-text {
  color: var(--t-secondary);
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
}
.thought-tools {
  margin-top: 8px;
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}
.tools-label {
  font-size: 11px;
  color: var(--t-tertiary);
}
.tool-chip {
  font-family: 'JetBrains Mono', monospace;
  font-size: 11px;
  padding: 2px 8px;
  background: rgba(168, 85, 247, 0.15);
  color: var(--c-accent-light);
  border-radius: 10px;
  border: 1px solid rgba(168, 85, 247, 0.25);
}
</style>
