<script setup>
import { computed } from 'vue'
import { renderMarkdown } from '@/utils/markdown'
import { copyText } from '@/utils/format'
import { useToastStore } from '@/stores/toast'

const props = defineProps({
  text: { type: String, default: '' },
  streaming: { type: Boolean, default: false }
})

const toast = useToastStore()

const html = computed(() => renderMarkdown(props.text, props.streaming))

async function onClick(e) {
  const btn = e.target.closest('[data-copy]')
  if (!btn) return
  const pre = btn.closest('pre')
  const code = pre?.querySelector('code')
  if (!code) return
  const ok = await copyText(code.innerText)
  if (ok) {
    btn.textContent = '已复制'
    setTimeout(() => (btn.textContent = '复制'), 1600)
  } else {
    toast.warn('复制失败，请手动选择')
  }
}
</script>

<template>
  <!-- eslint-disable-next-line vue/no-v-html -->
  <div class="md" @click="onClick" v-html="html" />
</template>

<style scoped>
.md {
  min-width: 0;
}
</style>
