<script setup>
import { ref } from 'vue'
import AppIcon from '@/components/common/AppIcon.vue'
import { useSettingsStore } from '@/stores/settings'

const props = defineProps({
  text: { type: String, default: '' },
  live: { type: Boolean, default: false }
})

const settings = useSettingsStore()
const opened = ref(settings.expandThinking)
const toggle = () => (opened.value = !opened.value)
</script>

<template>
  <div class="think" :class="{ 'is-live': live }">
    <button class="think__head" @click="toggle">
      <AppIcon name="bulb" :size="14" />
      <span class="think__label">{{ live ? '正在思考…' : '思考过程' }}</span>
      <AppIcon :name="opened ? 'chevronDown' : 'chevronRight'" :size="14" class="think__chev" />
    </button>

    <div class="think__collapse" :class="{ 'is-open': opened }">
      <div class="think__body">
        <div class="think__body-inner">
          <p class="think__text">{{ text || '（等待模型输出…）' }}</p>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.think {
  border: 1px dashed rgba(139, 92, 246, 0.32);
  background: rgba(139, 92, 246, 0.06);
  border-radius: var(--r-md);
  overflow: hidden;
  transition: border-color 0.25s, background 0.25s;
}
.think.is-live {
  border-color: rgba(139, 92, 246, 0.55);
  background: rgba(139, 92, 246, 0.1);
}

.think__head {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  border: none;
  background: transparent;
  color: #c4b5fd;
  cursor: pointer;
  font-size: 12.5px;
  font-weight: 500;
  text-align: left;
}
.think__head:hover {
  background: rgba(139, 92, 246, 0.08);
}
.think__label {
  flex: none;
}
.think__chev {
  margin-left: auto;
  color: var(--text-muted);
}

.think__collapse {
  display: grid;
  grid-template-rows: 0fr;
  transition: grid-template-rows 0.28s var(--ease);
}
.think__collapse.is-open {
  grid-template-rows: 1fr;
}
.think__body {
  overflow: hidden;
  min-height: 0;
}
.think__body-inner {
  padding: 0 12px 11px;
  max-height: 340px;
  overflow: auto;
  opacity: 0;
  transition: opacity 0.2s var(--ease);
}
.think__collapse.is-open .think__body-inner {
  opacity: 1;
  transition-delay: 0.08s;
}
.think__text {
  margin: 0;
  white-space: pre-wrap;
  font-size: 12.5px;
  line-height: 1.75;
  color: #b9b3e6;
  font-family: var(--font-mono);
}
</style>
