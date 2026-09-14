<script setup>
import { computed, ref } from 'vue'
import AppIcon from '@/components/common/AppIcon.vue'
import { hljs } from '@/utils/markdown'
import { copyText } from '@/utils/format'
import { useToastStore } from '@/stores/toast'

const props = defineProps({
  code: { type: String, default: '' },
  lang: { type: String, default: '' },
  /** 超过该行数折叠，避免长文件撑爆消息流 */
  collapseLines: { type: Number, default: 300 }
})

const toast = useToastStore()
const expanded = ref(false)

const lineCount = computed(() => props.code ? props.code.split('\n').length : 0)
const collapsed = computed(() => !expanded.value && lineCount.value > props.collapseLines)

const shown = computed(() =>
  collapsed.value ? props.code.split('\n').slice(0, props.collapseLines).join('\n') : props.code
)

const label = computed(() => props.lang || 'text')

const html = computed(() => {
  const raw = shown.value
  if (!raw) return ''
  if (props.lang && hljs.getLanguage(props.lang)) {
    try {
      return hljs.highlight(raw, { language: props.lang, ignoreIllegals: true }).value
    } catch {
      /* 高亮失败回退纯文本 */
    }
  }
  return raw.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
})

async function copy() {
  const ok = await copyText(props.code)
  toast[ok ? 'success' : 'warn'](ok ? '源码已复制' : '复制失败')
}
</script>

<template>
  <div class="code">
    <div class="code__bar">
      <span class="code__lang mono">{{ label }}</span>
      <span class="code__lines">{{ lineCount }} 行</span>
      <button class="code__act" @click="copy">
        <AppIcon name="copy" :size="12" /> 复制
      </button>
    </div>

    <!-- eslint-disable-next-line vue/no-v-html -->
    <pre class="code__body mono"><code v-html="html" /></pre>

    <button v-if="collapsed" class="code__more" @click="expanded = true">
      展开剩余 {{ lineCount - collapseLines }} 行
    </button>
  </div>
</template>

<style scoped>
.code {
  border: 1px solid var(--border);
  border-radius: 10px;
  overflow: hidden;
  background: rgba(0, 0, 0, 0.3);
}
.code__bar {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 5px 10px;
  border-bottom: 1px solid var(--border);
  background: rgba(255, 255, 255, 0.03);
  font-size: 11.5px;
  color: var(--text-muted);
}
.code__lang {
  font-size: 11px;
  color: #a5b4fc;
}
.code__lines {
  margin-right: auto;
}
.code__act {
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
.code__act:hover {
  color: var(--text);
  background: rgba(255, 255, 255, 0.07);
}
.code__body {
  margin: 0;
  padding: 10px 12px;
  max-height: 420px;
  overflow: auto;
  font-size: 12px;
  line-height: 1.65;
  color: var(--text-soft);
  white-space: pre;
}
.code__more {
  width: 100%;
  border: none;
  border-top: 1px solid var(--border);
  background: transparent;
  color: #a5b4fc;
  font-size: 12px;
  padding: 6px;
  cursor: pointer;
}
.code__more:hover {
  background: rgba(255, 255, 255, 0.05);
}
</style>
