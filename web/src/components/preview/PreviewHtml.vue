<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import AppIcon from '@/components/common/AppIcon.vue'
import { useSettingsStore } from '@/stores/settings'

const props = defineProps({
  source: { type: String, default: '' }
})

const emit = defineEmits(['fallback'])

const settings = useSettingsStore()
const loaded = ref(false)
const timedOut = ref(false)
const reloadKey = ref(0)
let timer = null

/**
 * 沙箱内策略：允许外部静态资源与内联脚本（生成的页面常用 CDN），
 * 但禁 base-uri 与 form-action，且 iframe 不给 allow-same-origin，
 * 因此页面拿不到父窗口、cookie 与本地存储。
 */
const CSP = "default-src 'none'; script-src 'unsafe-inline' https:; style-src 'unsafe-inline' https:; " +
  "img-src data: blob: https:; font-src data: https:; media-src blob: data:; connect-src https:; " +
  "form-action 'none'; base-uri 'none'"

/** srcdoc 为不透明源，sandbox 不带 allow-same-origin 时脚本无法触碰本站数据。 */
const sandboxAttr = computed(() => (settings.htmlPreviewScripts ? 'allow-scripts' : ''))

const doc = computed(() => injectCsp(props.source))

/** 把 CSP 注入到 head 最前：位置靠后才出现的 meta 无法约束已解析的资源。 */
function injectCsp(html) {
  const tag = `<meta http-equiv="Content-Security-Policy" content="${CSP}">`
  const head = html.search(/<head[^>]*>/i)
  if (head >= 0) {
    const end = html.indexOf('>', head) + 1
    return html.slice(0, end) + tag + html.slice(end)
  }
  const root = html.search(/<html[^>]*>/i)
  if (root >= 0) {
    const end = html.indexOf('>', root) + 1
    return html.slice(0, end) + '<head>' + tag + '</head>' + html.slice(end)
  }
  return `<!DOCTYPE html><html><head><meta charset="utf-8">${tag}</head><body>${html}</body></html>`
}

function onLoad() {
  loaded.value = true
  clearTimeout(timer)
}

/** 页面脚本死循环会导致 load 事件永不触发，超时后交回父组件降级为源码视图。 */
function startTimer() {
  clearTimeout(timer)
  timer = setTimeout(() => {
    timedOut.value = true
    emit('fallback', 'HTML 渲染超时')
  }, 8000)
}

function reload() {
  timedOut.value = false
  loaded.value = false
  reloadKey.value++
  startTimer()
}

onMounted(startTimer)
onBeforeUnmount(() => clearTimeout(timer))
</script>

<template>
  <div class="hp">
    <div class="hp__bar">
      <AppIcon name="shield" :size="12" />
      <span>沙箱预览</span>
      <span v-if="!settings.htmlPreviewScripts" class="hp__note">脚本已禁用</span>
      <button class="hp__act" @click="reload">
        <AppIcon name="refresh" :size="12" /> 重新加载
      </button>
    </div>

    <iframe
      v-if="!timedOut"
      :key="reloadKey"
      class="hp__frame"
      :srcdoc="doc"
      :sandbox="sandboxAttr"
      referrerpolicy="no-referrer"
      loading="lazy"
      @load="onLoad"
    />
    <p v-else class="hp__timeout">
      <AppIcon name="alert" :size="13" /> 渲染超时，已切换为源码视图
    </p>
  </div>
</template>

<style scoped>
.hp {
  border: 1px solid var(--border);
  border-radius: 10px;
  overflow: hidden;
  background: #fff;
}
.hp__bar {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 5px 10px;
  border-bottom: 1px solid var(--border);
  background: rgba(255, 255, 255, 0.04);
  font-size: 11.5px;
  color: var(--text-muted);
}
.hp__note {
  color: #fcd34d;
}
.hp__act {
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
.hp__act:hover {
  color: var(--text);
  background: rgba(255, 255, 255, 0.08);
}
.hp__frame {
  display: block;
  width: 100%;
  height: 420px;
  border: none;
  background: #fff;
}
.hp__timeout {
  display: flex;
  align-items: center;
  gap: 7px;
  margin: 0;
  padding: 14px;
  font-size: 12.5px;
  color: #fda4af;
}
</style>
