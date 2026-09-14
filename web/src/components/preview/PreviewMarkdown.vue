<script setup>
import MarkdownBlock from '@/components/chat/MarkdownBlock.vue'
import { copyText } from '@/utils/format'
import { useToastStore } from '@/stores/toast'
import AppIcon from '@/components/common/AppIcon.vue'

const props = defineProps({
  text: { type: String, default: '' }
})

const toast = useToastStore()

async function copy() {
  const ok = await copyText(props.text)
  toast[ok ? 'success' : 'warn'](ok ? '已复制 Markdown 原文' : '复制失败')
}
</script>

<template>
  <div class="mdwrap">
    <div class="mdwrap__bar">
      <span class="mono">markdown</span>
      <button class="mdwrap__act" @click="copy">
        <AppIcon name="copy" :size="12" /> 复制原文
      </button>
    </div>
    <div class="mdwrap__body">
      <MarkdownBlock :text="text" />
    </div>
  </div>
</template>

<style scoped>
.mdwrap {
  border: 1px solid var(--border);
  border-radius: 10px;
  overflow: hidden;
  background: rgba(0, 0, 0, 0.2);
}
.mdwrap__bar {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 5px 10px;
  border-bottom: 1px solid var(--border);
  background: rgba(255, 255, 255, 0.03);
  font-size: 11.5px;
  color: var(--text-muted);
}
.mdwrap__act {
  margin-left: auto;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  border: 1px solid var(--border);
  background: transparent;
  color: var(--text-muted);
  border-radius: 6px;
  padding: 1px 6px;
  font-size: 10.5px;
  cursor: pointer;
}
.mdwrap__act:hover {
  color: var(--text);
  background: rgba(255, 255, 255, 0.07);
}
.mdwrap__body {
  padding: 12px 14px;
  max-height: 460px;
  overflow: auto;
  font-size: 13px;
}
</style>
