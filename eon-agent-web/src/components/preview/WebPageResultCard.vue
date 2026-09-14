<script setup>
import { computed } from 'vue'
import AppIcon from '@/components/common/AppIcon.vue'
import WebPageCard from './WebPageCard.vue'
import { formatTokens } from '@/utils/format'

/**
 * 网页抓取结果卡片。与工具卡平级地出现在消息流中，抓取中给出进度，完成后直接展示站点卡片。
 */
const props = defineProps({
  /** 工具结果块：state ∈ generating | writing | done | error */
  block: { type: Object, required: true }
})

const pages = computed(() => props.block.pages || [])
const done = computed(() => props.block.state === 'done')

const okCount = computed(() => pages.value.filter((p) => p && p.success).length)

const chars = computed(() => pages.value.reduce((n, p) => n + (Number(p?.contentLength) || 0), 0))

const subText = computed(() => {
  if (props.block.state === 'generating') return '正在解析链接…'
  if (props.block.state === 'writing') return `正在抓取 ${pages.value.length || ''} 个网页…`.trim()
  if (props.block.state === 'error') return props.block.content || '抓取失败'
  const total = pages.value.length
  const fail = total - okCount.value
  const parts = [`${total} 个链接`]
  if (fail) parts.push(`${fail} 个失败`)
  if (chars.value) parts.push(`${formatTokens(chars.value)} 字符`)
  return parts.join(' · ')
})
</script>

<template>
  <div class="wr" :class="{ 'is-pending': !done, 'is-error': block.state === 'error' }">
    <header class="wr__head">
      <span class="wr__icon">
        <AppIcon name="globe" :size="15" />
      </span>
      <div class="wr__title">
        <div class="wr__name">抓取网页</div>
        <div class="wr__sub">{{ subText }}</div>
      </div>
      <span v-if="!done" class="wr__spin" />
      <AppIcon v-else-if="block.state === 'error'" name="alert" :size="14" />
    </header>

    <div v-if="done && pages.length" class="wr__body">
      <WebPageCard :pages="pages" :content="block.content" />
    </div>
  </div>
</template>

<style scoped>
.wr {
  border: 1px solid var(--border);
  border-radius: var(--r-md);
  overflow: hidden;
  background: rgba(255, 255, 255, 0.03);
}
.wr.is-pending {
  border-color: rgba(251, 191, 36, 0.3);
}
.wr.is-error {
  border-color: rgba(251, 113, 133, 0.32);
  background: rgba(251, 113, 133, 0.05);
}
.wr__head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 9px 12px;
}
.wr__icon {
  width: 26px;
  height: 26px;
  border-radius: 8px;
  display: grid;
  place-items: center;
  background: var(--grad-soft);
  color: #c4b5fd;
  flex: none;
}
.wr__title {
  min-width: 0;
  flex: 1;
}
.wr__name {
  font-size: 13px;
  font-weight: 600;
}
.wr__sub {
  font-size: 11px;
  color: var(--text-muted);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.is-error .wr__sub {
  color: #fda4af;
}
.wr__spin {
  width: 13px;
  height: 13px;
  border-radius: 50%;
  border: 2px solid rgba(251, 191, 36, 0.25);
  border-top-color: #fbbf24;
  animation: wr-spin 0.8s linear infinite;
  flex: none;
}
.wr__body {
  padding: 0 10px 10px;
}
@keyframes wr-spin {
  to {
    transform: rotate(360deg);
  }
}
</style>
