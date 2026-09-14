<script setup>
import { computed, ref, watch } from 'vue'
import AppIcon from '@/components/common/AppIcon.vue'
import PreviewCode from './PreviewCode.vue'
import PreviewHtml from './PreviewHtml.vue'
import PreviewImage from './PreviewImage.vue'
import PreviewMarkdown from './PreviewMarkdown.vue'
import * as api from '@/api/agent'
import { formatBytes } from '@/utils/format'
import { PREVIEW_MAX_BYTES, baseName, isRenderable, kindOf, langOf, needsText } from '@/utils/preview'
import { useSettingsStore } from '@/stores/settings'
import { useSessionStore } from '@/stores/session'

/**
 * 文件结果卡片。与工具卡平级地出现在消息流中：
 * 参数生成与写入阶段给出进度，完成后可直接展开预览（默认收起）。
 */
const props = defineProps({
  /** 工具结果块：state ∈ generating | writing | done | error */
  block: { type: Object, required: true }
})

const settings = useSettingsStore()
const session = useSessionStore()

/** 文件路径只在当前会话工作区内有效，故直接取当前会话 ID，不逐层透传。 */
const sessionId = computed(() => session.currentId)

const expanded = ref(false)
const loadState = ref('idle') // idle | loading | ready | error | too-large
const meta = ref(null)
const content = ref(null)
const kind = ref('')
const errorMsg = ref('')
const tab = ref('preview')
const forced = ref(false)

/** 尚未拿到路径时用阶段名占位，避免显示成「新文件」这种误导性标题。 */
const PENDING_NAME = {
  generating: '正在生成文件',
  writing: '正在写入文件',
  error: '写入失败'
}

const path = computed(() => props.block.path || '')
const name = computed(() => baseName(path.value) || PENDING_NAME[props.block.state] || '新文件')
const done = computed(() => props.block.state === 'done')

const rawUrl = computed(() => api.fileRawUrl(sessionId.value, path.value, false))
const downloadUrl = computed(() => api.fileRawUrl(sessionId.value, path.value, true))

const sizeText = computed(() => {
  const bytes = meta.value?.sizeBytes ?? props.block.sizeBytes
  if (bytes) return formatBytes(bytes)
  return props.block.fileSize || ''
})

/** 生成与写入阶段的进度文案，让用户知道此刻在做什么而不是干等。 */
const progressText = computed(() => {
  const chars = props.block.argsChars || 0
  if (props.block.state === 'generating') {
    return `正在生成文件内容… ${chars} 字符`
  }
  if (props.block.state === 'writing') {
    return '正在写入文件…'
  }
  if (props.block.state === 'error') {
    return props.block.content || '写入失败'
  }
  return ''
})

const canSwitch = computed(() => loadState.value === 'ready' && needsText(kind.value) && isRenderable(kind.value))
const sourceLang = computed(() =>
  kind.value === 'html' ? 'html' : kind.value === 'markdown' ? 'markdown' : langOf(name.value)
)

/** 展开时才拉取预览，避免折叠状态下白白请求。 */
watch(expanded, (v) => {
  if (v && loadState.value === 'idle') load()
})

async function load() {
  if (!path.value || !sessionId.value) return
  if (!forced.value && props.block.sizeBytes > PREVIEW_MAX_BYTES) {
    loadState.value = 'too-large'
    return
  }
  loadState.value = 'loading'
  errorMsg.value = ''
  try {
    meta.value = await api.getFileMeta(sessionId.value, path.value, settings.userId)
    kind.value = kindOf(meta.value)
    if (needsText(kind.value)) {
      content.value = await api.getFileContent(sessionId.value, path.value, settings.userId)
    }
    loadState.value = 'ready'
  } catch (e) {
    errorMsg.value = e?.message || '预览失败'
    loadState.value = 'error'
  }
}

function forceLoad() {
  forced.value = true
  loadState.value = 'idle'
  load()
}
</script>

<template>
  <div class="fr" :class="{ 'is-pending': !done }">
    <header class="fr__head">
      <AppIcon name="fileText" :size="15" />
      <div class="fr__title">
        <div class="fr__name">{{ name }}</div>
        <div class="fr__sub mono">
          <template v-if="done">
            <span>{{ path }}</span>
            <span v-if="sizeText"> · {{ sizeText }}</span>
            <span v-if="meta?.mime"> · {{ meta.mime }}</span>
            <span v-if="meta?.encoding && meta.encoding !== 'UTF-8'"> · {{ meta.encoding }}</span>
          </template>
          <span v-else>{{ progressText }}</span>
        </div>
      </div>
      <div class="fr__acts">
        <span v-if="!done" class="fr__spin" />
        <template v-else>
          <button class="fr__act" @click="expanded = !expanded">
            {{ expanded ? '收起预览' : '预览' }}
          </button>
          <a class="fr__act" :href="rawUrl" target="_blank" rel="noopener noreferrer" title="外部打开">
            <AppIcon name="external" :size="13" />
          </a>
          <a class="fr__act" :href="downloadUrl" title="下载">
            <AppIcon name="download" :size="13" />
          </a>
        </template>
      </div>
    </header>

    <div v-if="expanded && done" class="fr__body">
      <div v-if="canSwitch" class="fr__tabs">
        <button :class="{ 'is-on': tab === 'preview' }" @click="tab = 'preview'">渲染结果</button>
        <button :class="{ 'is-on': tab === 'source' }" @click="tab = 'source'">源码</button>
      </div>

      <p v-if="loadState === 'loading'" class="fr__hint">
        <span class="fr__spin" /> 正在加载预览…
      </p>

      <div v-else-if="loadState === 'too-large'" class="fr__hint fr__hint--warn">
        <AppIcon name="alert" :size="13" />
        <span>文件较大（{{ sizeText }}），未自动预览</span>
        <button class="fr__link" @click="forceLoad">仍要预览</button>
      </div>

      <div v-else-if="loadState === 'error'" class="fr__hint fr__hint--warn">
        <AppIcon name="alert" :size="13" />
        <span>{{ errorMsg }}</span>
        <button class="fr__link" @click="load">重试</button>
      </div>

      <template v-else-if="loadState === 'ready'">
        <div v-if="!isRenderable(kind)" class="fr__hint">
          <AppIcon name="file" :size="13" /> 二进制文件，无法预览，可下载查看
        </div>

        <PreviewImage v-else-if="kind === 'image'" :src="rawUrl" :name="name" />

        <iframe v-else-if="kind === 'pdf'" class="fr__pdf" :src="rawUrl" title="PDF 预览" />

        <template v-else-if="tab === 'preview'">
          <PreviewHtml v-if="kind === 'html'" :source="content?.content || ''" @fallback="tab = 'source'" />
          <PreviewMarkdown v-else-if="kind === 'markdown'" :text="content?.content || ''" />
          <PreviewCode v-else :code="content?.content || ''" :lang="sourceLang" />
        </template>

        <PreviewCode v-else :code="content?.content || ''" :lang="sourceLang" />

        <p v-if="content?.truncated" class="fr__truncated">
          文件超过 {{ Math.round(content.limit / 1024) }} KB，仅预览前一部分，完整内容请下载查看
        </p>
      </template>
    </div>
  </div>
</template>

<style scoped>
.fr {
  border: 1px solid var(--border);
  border-radius: var(--r-md);
  overflow: hidden;
  background: rgba(255, 255, 255, 0.03);
}
.fr.is-pending {
  border-color: rgba(251, 191, 36, 0.3);
}
.fr__head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 9px 12px;
}
.fr__title {
  min-width: 0;
  flex: 1;
}
.fr__name {
  font-size: 13px;
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.fr__sub {
  font-size: 10.5px;
  color: var(--text-muted);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.fr__acts {
  display: flex;
  align-items: center;
  gap: 4px;
  flex: none;
}
.fr__act {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  height: 24px;
  padding: 0 8px;
  border-radius: 6px;
  border: 1px solid var(--border);
  background: transparent;
  color: var(--text-muted);
  font-size: 11.5px;
  cursor: pointer;
}
.fr__act:hover {
  color: var(--text);
  background: rgba(255, 255, 255, 0.07);
}
.fr__spin {
  width: 13px;
  height: 13px;
  border-radius: 50%;
  border: 2px solid rgba(251, 191, 36, 0.25);
  border-top-color: #fbbf24;
  animation: fr-spin 0.8s linear infinite;
  display: inline-block;
}
.fr__body {
  padding: 0 10px 10px;
}
.fr__tabs {
  display: flex;
  gap: 4px;
  padding: 0 0 8px;
}
.fr__tabs button {
  border: 1px solid var(--border);
  background: transparent;
  color: var(--text-muted);
  border-radius: 6px;
  padding: 2px 10px;
  font-size: 11.5px;
  cursor: pointer;
}
.fr__tabs button.is-on {
  color: var(--text);
  border-color: var(--border-strong);
  background: rgba(255, 255, 255, 0.08);
}
.fr__hint {
  display: flex;
  align-items: center;
  gap: 7px;
  margin: 0;
  font-size: 12.3px;
  color: var(--text-muted);
}
.fr__hint--warn {
  color: #fda4af;
}
.fr__link {
  border: none;
  background: transparent;
  color: #a5b4fc;
  font-size: 12px;
  cursor: pointer;
  padding: 0;
}
.fr__link:hover {
  text-decoration: underline;
}
.fr__pdf {
  width: 100%;
  height: 460px;
  border: 1px solid var(--border);
  border-radius: 10px;
  background: #fff;
}
.fr__truncated {
  margin: 8px 0 0;
  font-size: 11.5px;
  color: var(--text-muted);
}
@keyframes fr-spin {
  to {
    transform: rotate(360deg);
  }
}
</style>
