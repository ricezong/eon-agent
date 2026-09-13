<script setup>
import { nextTick, ref, watch } from 'vue'
import AppIcon from '@/components/common/AppIcon.vue'

const props = defineProps({
  streaming: { type: Boolean, default: false },
  offline: { type: Boolean, default: false }
})

const emit = defineEmits(['send', 'stop'])

const text = ref('')
const area = ref(null)
const focused = ref(false)

function resize() {
  const el = area.value
  if (!el) return
  el.style.height = 'auto'
  el.style.height = `${Math.min(el.scrollHeight, 180)}px`
}

watch(text, () => nextTick(resize))

function submit() {
  const v = text.value.trim()
  if (!v || props.streaming) return
  emit('send', v)
  text.value = ''
  nextTick(resize)
}

function onKeydown(e) {
  // 中文输入法组合期间不拦截回车
  if (e.isComposing) return
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    submit()
  }
}

function focus() {
  area.value?.focus()
}

function setText(v) {
  text.value = v
  nextTick(() => {
    resize()
    focus()
  })
}

defineExpose({ focus, setText })
</script>

<template>
  <div class="composer-wrap">
    <div class="composer glass-strong" :class="{ 'is-focus': focused, 'is-busy': streaming }">
      <textarea
        ref="area"
        v-model="text"
        class="composer__area"
        rows="1"
        :placeholder="
          streaming
            ? 'Eon 正在执行任务，可随时点击停止…'
            : '给 Eon 下达任务，Enter 发送，Shift + Enter 换行'
        "
        @keydown="onKeydown"
        @focus="focused = true"
        @blur="focused = false"
      />

      <div class="composer__bar">
        <div class="composer__meta">
          <span v-if="offline" class="composer__warn">
            <AppIcon name="alert" :size="12" /> 后端未连接
          </span>
          <span v-else class="composer__hint">
            <AppIcon name="zap" :size="12" /> 可调用搜索 / 文件 / 阅读等工具
          </span>
          <span class="composer__count">{{ text.length }}</span>
        </div>

        <button
          v-if="streaming"
          class="composer__btn composer__btn--stop"
          title="停止生成（调用 /api/interrupt）"
          @click="emit('stop')"
        >
          <span class="composer__stop-icon" />
          停止
        </button>
        <button
          v-else
          class="composer__btn"
          :disabled="!text.trim()"
          title="发送（Enter）"
          @click="submit"
        >
          <AppIcon name="send" :size="15" />
          <span class="composer__btn-text">发送</span>
        </button>
      </div>
    </div>

    <p class="composer__note">
      内容由 AI 生成，执行文件写入等敏感操作前请确认。
    </p>
  </div>
</template>

<style scoped>
.composer-wrap {
  padding: 12px 20px 16px;
  flex: none;
}

.composer {
  max-width: 900px;
  margin: 0 auto;
  border-radius: var(--r-lg);
  padding: 12px 12px 10px;
  box-shadow: var(--shadow-md);
  transition: box-shadow 0.25s var(--ease), border-color 0.25s var(--ease), transform 0.2s var(--ease);
}
.composer.is-focus {
  border-color: rgba(139, 92, 246, 0.45);
  box-shadow: 0 0 0 3px rgba(139, 92, 246, 0.12), var(--shadow-lg);
}
.composer.is-busy {
  border-color: rgba(251, 191, 36, 0.32);
}

.composer__area {
  width: 100%;
  border: none;
  outline: none;
  background: transparent;
  resize: none;
  color: var(--text);
  font-size: 14.5px;
  line-height: 1.7;
  max-height: 180px;
  padding: 4px 6px;
  font-family: inherit;
}
.composer__area::placeholder {
  color: var(--text-muted);
}

.composer__bar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 8px;
  padding-top: 8px;
  border-top: 1px solid var(--border);
}

.composer__meta {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 11.5px;
  color: var(--text-muted);
  min-width: 0;
  overflow: hidden;
}
.composer__hint,
.composer__warn {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  white-space: nowrap;
}
.composer__warn {
  color: #fda4af;
}
.composer__count {
  margin-left: 2px;
  font-family: var(--font-mono);
  opacity: 0.7;
}

.composer__btn {
  margin-left: auto;
  display: inline-flex;
  align-items: center;
  gap: 7px;
  padding: 8px 16px;
  border-radius: 11px;
  border: none;
  background: var(--grad-brand);
  color: #fff;
  font-weight: 600;
  font-size: 13.5px;
  cursor: pointer;
  flex: none;
  box-shadow: 0 8px 22px rgba(139, 92, 246, 0.3);
  transition: transform 0.18s var(--ease), box-shadow 0.18s var(--ease), opacity 0.18s;
}
.composer__btn:hover:not(:disabled) {
  transform: translateY(-1px);
  box-shadow: 0 12px 28px rgba(139, 92, 246, 0.42);
}
.composer__btn:disabled {
  opacity: 0.42;
  cursor: not-allowed;
  box-shadow: none;
}
.composer__btn--stop {
  background: rgba(251, 113, 133, 0.16);
  border: 1px solid rgba(251, 113, 133, 0.4);
  color: #fda4af;
  box-shadow: none;
}
.composer__btn--stop:hover {
  background: rgba(251, 113, 133, 0.24);
  box-shadow: none;
}
.composer__stop-icon {
  width: 10px;
  height: 10px;
  border-radius: 2px;
  background: currentColor;
}

.composer__note {
  max-width: 900px;
  margin: 8px auto 0;
  text-align: center;
  font-size: 11px;
  color: var(--text-muted);
}

@media (max-width: 640px) {
  .composer-wrap {
    padding: 10px 12px 12px;
  }
  .composer__btn-text {
    display: none;
  }
  .composer__btn {
    padding: 8px 12px;
  }
  .composer__hint {
    display: none;
  }
}
</style>
