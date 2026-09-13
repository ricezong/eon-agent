<script setup>
import { computed } from 'vue'
import AppIcon from '@/components/common/AppIcon.vue'
import MarkdownBlock from './MarkdownBlock.vue'
import ThinkingBlock from './ThinkingBlock.vue'
import ToolCallCard from './ToolCallCard.vue'
import { copyText, clockTime, formatTokens } from '@/utils/format'
import { useToastStore } from '@/stores/toast'

const props = defineProps({
  message: { type: Object, required: true },
  streaming: { type: Boolean, default: false }
})

const toast = useToastStore()

const STOP_TEXT = {
  task_completed: '任务完成',
  user_interrupted: '已被中断',
  max_steps_reached: '达到最大步数上限',
  budget_exceeded: 'Token 预算超限',
  loop_detected: '检测到重复循环已停止',
  gate_rejected: '破坏性操作被安全门禁拦截',
  replay_completed: '历史会话回放',
  unexpected_error: '执行异常终止'
}

const isStreaming = computed(() => props.streaming || props.message.status === 'streaming')

const plainText = computed(() =>
  props.message.blocks
    .filter((b) => b.kind === 'text')
    .map((b) => b.target)
    .join('\n\n')
)

const statusText = computed(() => {
  if (props.message.status === 'error') return '执行出错'
  if (props.message.status === 'stopped') return '已停止'
  if (props.message.status === 'terminated') return '已终止'
  const r = props.message.stopReason
  if (!r || r === 'replay_completed') return ''
  return STOP_TEXT[r] || r
})

const statusTone = computed(() => {
  if (props.message.status === 'error') return 'pill-err'
  if (props.message.status === 'stopped' || props.message.status === 'terminated') return 'pill-warn'
  return 'pill-ok'
})

function isTyping(b) {
  return isStreaming.value && !b.closed && b.shown.length < b.target.length
}

async function copyAll() {
  const ok = await copyText(plainText.value)
  toast[ok ? 'success' : 'warn'](ok ? '回复已复制' : '复制失败')
}
</script>

<template>
  <div class="bubble">
    <div class="bubble__avatar">
      <span class="bubble__avatar-inner">E</span>
    </div>

    <div class="bubble__main">
      <div class="bubble__head">
        <span class="bubble__who">Eon</span>
        <span class="bubble__time">{{ clockTime(message.createdAt) }}</span>
      </div>

      <!-- 内容块 -->
      <div class="bubble__blocks">
        <template v-for="b in message.blocks" :key="b.id">
          <ThinkingBlock v-if="b.kind === 'thinking'" :text="b.text" :live="isStreaming && !b.closed" />

          <div v-else-if="b.kind === 'text'" class="bubble__text">
            <MarkdownBlock :text="b.shown" :streaming="isTyping(b)" />
            <span v-if="isTyping(b)" class="caret" />
          </div>

          <ToolCallCard
            v-else-if="b.kind === 'tool'"
            :name="b.name"
            :input="b.input"
            :state="b.state"
            :content="b.content"
            :structured="b.structured"
          />
        </template>

        <!-- 等待首个 token -->
        <div v-if="isStreaming && !message.blocks.length" class="waiting">
          <span /><span /><span />
          <em>正在思考…</em>
        </div>
      </div>

      <!-- 错误 -->
      <div v-if="message.error" class="bubble__error">
        <AppIcon name="alert" :size="14" />
        <span>{{ message.error.message }}</span>
        <span class="bubble__error-type mono">{{ message.error.type }}</span>
      </div>

      <!-- 底部信息 -->
      <div class="bubble__foot">
        <span v-if="statusText && !isStreaming" class="pill" :class="statusTone">
          <AppIcon name="check" :size="12" />
          {{ statusText }}
        </span>

        <span v-if="message.usage" class="usage">
          <span class="usage__item" title="提示 token">
            <AppIcon name="arrowRight" :size="11" />{{ formatTokens(message.usage.prompt) }}
          </span>
          <span class="usage__item" title="生成 token">
            <AppIcon name="sparkles" :size="11" />{{ formatTokens(message.usage.completion) }}
          </span>
          <span class="usage__item usage__item--total" title="累计 token">
            <AppIcon name="activity" :size="11" />{{ formatTokens(message.usage.total) }}
          </span>
        </span>

        <button v-if="plainText && !isStreaming" class="bubble__action" title="复制回复" @click="copyAll">
          <AppIcon name="copy" :size="13" />
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.bubble {
  display: flex;
  gap: 12px;
  align-items: flex-start;
}

.bubble__avatar {
  width: 32px;
  height: 32px;
  border-radius: 11px;
  background: var(--grad-brand);
  display: grid;
  place-items: center;
  flex: none;
  box-shadow: 0 6px 18px rgba(139, 92, 246, 0.35);
  margin-top: 2px;
}
.bubble__avatar-inner {
  font-weight: 800;
  color: #fff;
  font-size: 15px;
}

.bubble__main {
  flex: 1;
  min-width: 0;
}

.bubble__head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}
.bubble__who {
  font-weight: 650;
  font-size: 13.5px;
}
.bubble__time {
  font-size: 11px;
  color: var(--text-muted);
}
.bubble__blocks {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.bubble__text {
  position: relative;
  min-width: 0;
}

.caret {
  display: inline-block;
  width: 7px;
  height: 15px;
  margin-left: 2px;
  vertical-align: text-bottom;
  background: linear-gradient(180deg, #a78bfa, #22d3ee);
  border-radius: 2px;
  animation: blink 1s step-end infinite;
}

.waiting {
  display: flex;
  align-items: center;
  gap: 5px;
  color: var(--text-muted);
  font-size: 13px;
  padding: 4px 0;
}
.waiting span {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #8b5cf6;
  animation: bounce 1.2s infinite;
}
.waiting span:nth-child(2) {
  animation-delay: 0.15s;
}
.waiting span:nth-child(3) {
  animation-delay: 0.3s;
}
.waiting em {
  margin-left: 6px;
  font-style: normal;
  font-size: 12.5px;
}
@keyframes bounce {
  0%,
  60%,
  100% {
    transform: translateY(0);
    opacity: 0.45;
  }
  30% {
    transform: translateY(-5px);
    opacity: 1;
  }
}

.bubble__error {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 10px;
  padding: 9px 12px;
  border-radius: var(--r-sm);
  border: 1px solid rgba(251, 113, 133, 0.32);
  background: rgba(251, 113, 133, 0.1);
  color: #fecdd3;
  font-size: 12.8px;
}
.bubble__error-type {
  margin-left: auto;
  font-size: 10.5px;
  opacity: 0.75;
}

.bubble__foot {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 10px;
  flex-wrap: wrap;
}

.usage {
  display: flex;
  gap: 6px;
}
.usage__item {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  font-size: 11px;
  color: var(--text-muted);
  border: 1px solid var(--border);
  border-radius: 99px;
  padding: 2px 8px;
}
.usage__item--total {
  color: #a5b4fc;
  border-color: rgba(139, 92, 246, 0.28);
  background: rgba(139, 92, 246, 0.1);
}

.bubble__action {
  border: none;
  background: transparent;
  color: var(--text-muted);
  cursor: pointer;
  padding: 4px;
  border-radius: 7px;
  display: grid;
  place-items: center;
  opacity: 0;
  transition: opacity 0.18s, color 0.18s, background 0.18s;
}
.bubble:hover .bubble__action {
  opacity: 1;
}
.bubble__action:hover {
  color: var(--text);
  background: rgba(255, 255, 255, 0.08);
}

@media (max-width: 640px) {
  .bubble__action {
    opacity: 1;
  }
}
</style>
