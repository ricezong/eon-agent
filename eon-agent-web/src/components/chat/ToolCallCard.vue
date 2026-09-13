<script setup>
import { computed, ref } from 'vue'
import AppIcon from '@/components/common/AppIcon.vue'
import { toolMeta } from '@/config/tools'
import { formatBytes, safeJson } from '@/utils/format'
import { useSettingsStore } from '@/stores/settings'
import { copyText } from '@/utils/format'
import { useToastStore } from '@/stores/toast'

const props = defineProps({
  name: { type: String, default: '' },
  input: { type: [String, Object], default: '' },
  state: { type: String, default: 'running' }, // running | success | error
  content: { type: String, default: '' },
  structured: { type: Object, default: null }
})

const settings = useSettingsStore()
const toast = useToastStore()
const opened = ref(!settings.collapseToolResult)
const fullResult = ref(false)

const meta = computed(() => toolMeta(props.name))

const args = computed(() => {
  const v = safeJson(props.input, null)
  return v && typeof v === 'object' ? v : props.input
})

const resultText = computed(() => {
  const s = props.structured
  if (s && s.type === 'text' && s.text) return s.text
  return props.content || ''
})

const resultView = computed(() => props.structured || null)

const stateText = computed(() =>
  props.state === 'running' ? '执行中' : props.state === 'error' ? '失败' : '完成'
)

const stateClass = computed(() =>
  props.state === 'running' ? 'is-running' : props.state === 'error' ? 'is-error' : 'is-ok'
)

const displayed = computed(() => {
  const t = resultText.value
  if (fullResult.value || t.length <= 900) return t
  return `${t.slice(0, 900)}\n…（已折叠，共 ${t.length} 字符）`
})

function toggle() {
  opened.value = !opened.value
}

async function copyResult() {
  const ok = await copyText(resultText.value)
  toast[ok ? 'success' : 'warn'](ok ? '结果已复制' : '复制失败')
}
</script>

<template>
  <div class="tool" :class="[stateClass, { 'is-open': opened }]">
    <button class="tool__head" @click="toggle">
      <span class="tool__icon">
        <AppIcon :name="meta.icon" :size="15" />
      </span>

      <span class="tool__title">
        <span class="tool__name">{{ meta.label }}</span>
        <span class="tool__raw mono">{{ name }}</span>
      </span>

      <span class="tool__state">
        <span v-if="state === 'running'" class="tool__spinner" />
        <AppIcon v-else :name="state === 'error' ? 'alert' : 'check'" :size="13" />
        <span class="tool__state-text">{{ stateText }}</span>
      </span>

      <AppIcon :name="opened ? 'chevronDown' : 'chevronRight'" :size="14" class="tool__chev" />
    </button>

    <div class="tool__collapse" :class="{ 'is-open': opened }">
      <div class="tool__body">
       <div class="tool__body-inner">
        <!-- 入参 -->
        <section v-if="args" class="tool__section">
          <h4 class="tool__label">
            <AppIcon name="code" :size="12" /> 调用参数
          </h4>
          <pre class="tool__code mono">{{ typeof args === 'string' ? args : JSON.stringify(args, null, 2) }}</pre>
        </section>

        <!-- 结果 -->
        <section v-if="state !== 'running'" class="tool__section">
          <h4 class="tool__label">
            <AppIcon name="arrowRight" :size="12" /> 执行结果
            <button v-if="resultText" class="tool__copy" @click.stop="copyResult">
              <AppIcon name="copy" :size="12" /> 复制
            </button>
          </h4>

          <!-- 文件 -->
          <div v-if="resultView?.type === 'file'" class="tool__file">
            <AppIcon name="fileText" :size="16" />
            <span class="mono">{{ resultView.filePath }}</span>
            <span class="tool__size">{{ resultView.fileSize || '' }}</span>
          </div>

          <!-- 目录列表 -->
          <ul v-else-if="resultView?.type === 'dir_list'" class="tool__dir">
            <li v-for="(e, i) in resultView.entries || []" :key="i">
              <AppIcon :name="e.type === 'dir' ? 'folder' : 'file'" :size="13" />
              <span class="mono">{{ e.name }}</span>
              <span v-if="e.size" class="tool__size">{{ formatBytes(e.size) }}</span>
            </li>
            <li v-if="!(resultView.entries || []).length" class="muted">空目录</li>
          </ul>

          <!-- artifact -->
          <div v-else-if="resultView?.type === 'artifact'" class="tool__file">
            <AppIcon name="layers" :size="16" />
            <span class="mono">artifact://{{ resultView.artifactId }}</span>
          </div>

          <!-- 文本 -->
          <pre v-else-if="resultText" class="tool__result mono">{{ displayed }}</pre>
          <p v-else class="muted tool__empty">（无输出）</p>

          <button
            v-if="resultText.length > 900"
            class="tool__more"
            @click.stop="fullResult = !fullResult"
          >
            {{ fullResult ? '收起' : '展开全部' }}
          </button>
        </section>

        <p v-else class="tool__waiting">
          <span class="tool__spinner" /> 正在执行工具…
        </p>
       </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.tool {
  border: 1px solid var(--border);
  background: rgba(255, 255, 255, 0.03);
  border-radius: var(--r-md);
  overflow: hidden;
  transition: border-color 0.22s, background 0.22s, box-shadow 0.22s;
}
.tool:hover {
  border-color: var(--border-strong);
  background: rgba(255, 255, 255, 0.05);
}
.tool.is-running {
  border-color: rgba(251, 191, 36, 0.35);
}
.tool.is-error {
  border-color: rgba(251, 113, 133, 0.35);
  background: rgba(251, 113, 133, 0.05);
}
.tool.is-ok {
  border-color: rgba(52, 211, 153, 0.22);
}

.tool__head {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 9px 12px;
  border: none;
  background: transparent;
  color: var(--text);
  cursor: pointer;
  text-align: left;
  font-size: 13px;
}
.tool__icon {
  width: 26px;
  height: 26px;
  border-radius: 8px;
  display: grid;
  place-items: center;
  background: var(--grad-soft);
  color: #c4b5fd;
  flex: none;
}
.tool__title {
  display: flex;
  align-items: baseline;
  gap: 6px;
  flex: 1;
  min-width: 0;
}
.tool__name {
  font-weight: 600;
  font-size: 13px;
}
.tool__raw {
  font-size: 10.5px;
  color: var(--text-muted);
}
.tool__state {
  display: flex;
  align-items: center;
  gap: 5px;
  font-size: 11.5px;
  color: var(--text-muted);
  flex: none;
}
.is-ok .tool__state {
  color: #6ee7b7;
}
.is-error .tool__state {
  color: #fda4af;
}
.is-running .tool__state {
  color: #fcd34d;
}
.tool__chev {
  color: var(--text-muted);
  flex: none;
}

.tool__spinner {
  width: 12px;
  height: 12px;
  border-radius: 50%;
  border: 2px solid rgba(251, 191, 36, 0.25);
  border-top-color: #fbbf24;
  animation: spin 0.8s linear infinite;
  display: inline-block;
}

.tool__collapse {
  display: grid;
  grid-template-rows: 0fr;
  transition: grid-template-rows 0.28s var(--ease);
}
.tool__collapse.is-open {
  grid-template-rows: 1fr;
}
.tool__body {
  overflow: hidden;
  min-height: 0;
  opacity: 0;
  transition: opacity 0.2s var(--ease);
}
.tool__collapse.is-open .tool__body {
  opacity: 1;
  transition-delay: 0.08s;
}
.tool__body-inner {
  padding: 0 12px 12px;
  border-top: 1px solid var(--border);
  margin-top: 2px;
  padding-top: 10px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.tool__section {
  min-width: 0;
}
.tool__label {
  display: flex;
  align-items: center;
  gap: 6px;
  margin: 0 0 6px;
  font-size: 11.5px;
  font-weight: 600;
  color: var(--text-muted);
  letter-spacing: 0.04em;
}
.tool__copy {
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
.tool__copy:hover {
  color: var(--text);
  background: rgba(255, 255, 255, 0.07);
}
.tool__code,
.tool__result {
  margin: 0;
  padding: 10px 12px;
  border-radius: 10px;
  background: rgba(0, 0, 0, 0.32);
  border: 1px solid var(--border);
  font-size: 12px;
  line-height: 1.65;
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 320px;
  overflow: auto;
  color: var(--text-soft);
}
.tool__result {
  max-height: 260px;
}
.tool__file,
.tool__dir li {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12.3px;
  padding: 5px 0;
  color: var(--text-soft);
}
.tool__dir {
  list-style: none;
  margin: 0;
  padding: 0 0 0 2px;
  max-height: 260px;
  overflow: auto;
}
.tool__size {
  margin-left: auto;
  color: var(--text-muted);
  font-size: 11px;
}
.tool__more {
  margin-top: 6px;
  border: none;
  background: transparent;
  color: #a5b4fc;
  font-size: 12px;
  cursor: pointer;
  padding: 0;
}
.tool__more:hover {
  text-decoration: underline;
}
.tool__waiting {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0;
  font-size: 12.5px;
  color: var(--text-muted);
}
.tool__empty {
  font-size: 12px;
  margin: 0;
}

</style>
