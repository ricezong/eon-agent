<script setup>
import { computed, watch, onMounted, onBeforeUnmount } from 'vue'
import { renderMarkdown, formatTokens } from '../utils/markdown'
import { useTypewriter } from '../composables/useTypewriter'
import ToolBubble from './ToolBubble.vue'
import ThoughtBlock from './ThoughtBlock.vue'
import Icon from './Icon.vue'

const props = defineProps({
  message: { type: Object, required: true },
})

const isUser = computed(() => props.message.role === 'user')
const isRunning = computed(() => props.message.status === 'running')

// 打字机效果（参考 frontend_old：buffer-based + 自适应步长）
const { displayed, typing, setBuffer, finish, reset } = useTypewriter()

// 监听 content 变化：DONE 事件到达时启动打字机
watch(
  () => props.message.content,
  (newVal) => {
    if (isUser.value) return
    if (!newVal) return
    // 内容到达（DONE 事件），启动打字机
    setBuffer(newVal)
  }
)

// 监听状态变化：终止/出错时立即完成打字
watch(
  () => props.message.status,
  (s) => {
    if (s === 'terminated' || s === 'error') {
      finish()
    }
  }
)

onMounted(() => {
  if (!isUser.value && props.message.content) {
    // 已完成的消息直接显示全文（切换会话回来时不重复打字）
    if (props.message.status === 'done' || props.message.status === 'terminated' || props.message.status === 'error') {
      displayed.value = props.message.content
    } else {
      setBuffer(props.message.content)
    }
  }
})

onBeforeUnmount(() => {
  reset()
})

const renderedContent = computed(() => renderMarkdown(displayed.value))
const renderedUserContent = computed(() => renderMarkdown(props.message.content))

const toolEvents = computed(() =>
  (props.message.events || []).filter((e) => e.type === 'TOOL_START' || e.type === 'TOOL_RESULT')
)

const thoughtEvents = computed(() =>
  (props.message.events || []).filter((e) => e.type === 'LLM_RESPONSE')
)

const showTypingIndicator = computed(
  () => isRunning.value && !props.message.content && toolEvents.value.length === 0 && thoughtEvents.value.length === 0
)
</script>

<template>
  <div :class="['message', isUser ? 'message-user' : 'message-assistant']">
    <!-- 头像 -->
    <div :class="['avatar', isUser ? 'avatar-user' : 'avatar-assistant']">
      <Icon v-if="isUser" name="user" :size="16" />
      <span v-else class="avatar-text">E</span>
    </div>

    <!-- 消息体 -->
    <div class="message-body">
      <!-- 思考过程 -->
      <ThoughtBlock
        v-for="(ev, i) in thoughtEvents"
        :key="'thought-' + i"
        :thought="ev.thought"
        :tool-names="ev.toolNames"
        :turn="ev.turn"
      />

      <!-- 工具调用 -->
      <ToolBubble
        v-for="(ev, i) in toolEvents"
        :key="'tool-' + i"
        :event="ev"
      />

      <!-- 等待指示器 -->
      <div v-if="showTypingIndicator" class="typing-indicator">
        <span></span><span></span><span></span>
      </div>

      <!-- 用户消息内容 -->
      <div v-if="isUser" class="content user-content" v-html="renderedUserContent"></div>

      <!-- 助手消息内容（打字机效果，点击跳过） -->
      <div v-else-if="displayed" class="content assistant-content" @click="finish">
        <div class="markdown-body" v-html="renderedContent"></div>
        <span v-if="typing" class="cursor">▋</span>
      </div>

      <!-- 错误/终止 -->
      <div v-if="message.status === 'error'" class="error-box">
        <Icon name="x-circle" :size="16" />
        <span>{{ message.error || '执行出错' }}</span>
      </div>
      <div v-else-if="message.status === 'terminated'" class="terminated-box">
        <Icon name="ban" :size="16" />
        <span>{{ message.error || '任务被终止' }}</span>
      </div>

      <!-- 元信息 -->
      <div v-if="!isUser && (message.tokens || message.turn) && message.status === 'done'" class="meta">
        <span v-if="message.turn" class="meta-item">
          <Icon name="refresh" :size="11" />
          {{ message.turn }} 轮
        </span>
        <span v-if="message.tokens" class="meta-item">
          <Icon name="zap" :size="11" />
          {{ formatTokens(message.tokens) }} tokens
        </span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.message {
  display: flex;
  gap: 12px;
  margin-bottom: 24px;
  align-items: flex-start;
}

/* 用户消息靠右 */
.message-user {
  flex-direction: row-reverse;
}

/* 头像 */
.avatar {
  width: 32px;
  height: 32px;
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  font-weight: 600;
}
.avatar-user {
  background: var(--bg-surface);
  color: var(--t-secondary);
  border: 1px solid var(--border-default);
}
.avatar-assistant {
  background: var(--grad-primary);
  color: white;
  box-shadow: 0 2px 8px rgba(99, 102, 241, 0.4);
}
.avatar-text {
  font-size: 15px;
  font-weight: 800;
  font-family: 'Georgia', serif;
}

/* 消息体 */
.message-body {
  min-width: 0;
  max-width: calc(100% - 48px);
  display: flex;
  flex-direction: column;
}
.message-user .message-body {
  align-items: flex-end;
}
.message-assistant .message-body {
  align-items: flex-start;
}

.content {
  font-size: 14px;
  line-height: 1.7;
  word-break: break-word;
}

/* 用户消息：气泡样式 */
.user-content {
  color: var(--t-primary);
  background: var(--grad-primary);
  padding: 12px 16px;
  border-radius: 16px 16px 4px 16px;
  display: inline-block;
  max-width: 100%;
  box-shadow: 0 2px 12px rgba(99, 102, 241, 0.25);
}
.user-content :deep(.markdown-body) {
  background: transparent;
  padding: 0;
}
.user-content :deep(p) {
  margin: 0;
}
.user-content :deep(p) + :deep(p) {
  margin-top: 0.5em;
}

/* 助手消息：无气泡，直接展示 */
.assistant-content {
  color: var(--t-primary);
  position: relative;
  background: var(--bg-surface);
  padding: 14px 18px;
  border-radius: 4px 16px 16px 16px;
  border: 1px solid var(--border-default);
  max-width: 100%;
  cursor: pointer;
}

.cursor {
  display: inline-block;
  color: var(--c-primary);
  animation: blink 1s infinite;
  margin-left: 2px;
  font-weight: 300;
}
@keyframes blink {
  0%, 50% { opacity: 1; }
  51%, 100% { opacity: 0; }
}

.typing-indicator {
  display: flex;
  gap: 4px;
  padding: 12px 16px;
  background: var(--bg-surface);
  border-radius: 4px 16px 16px 16px;
  border: 1px solid var(--border-default);
  align-self: flex-start;
}
.typing-indicator span {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--c-primary);
  animation: bounce 1.4s infinite;
}
.typing-indicator span:nth-child(2) { animation-delay: 0.2s; }
.typing-indicator span:nth-child(3) { animation-delay: 0.4s; }
@keyframes bounce {
  0%, 60%, 100% { transform: translateY(0); opacity: 0.4; }
  30% { transform: translateY(-6px); opacity: 1; }
}

.error-box, .terminated-box {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 8px;
  padding: 10px 14px;
  border-radius: var(--r-md);
  font-size: 13px;
}
.error-box {
  background: rgba(239, 68, 68, 0.1);
  border: 1px solid rgba(239, 68, 68, 0.3);
  color: var(--c-danger-light);
}
.terminated-box {
  background: rgba(245, 158, 11, 0.1);
  border: 1px solid rgba(245, 158, 11, 0.3);
  color: var(--c-warning-light);
}

.meta {
  display: flex;
  gap: 12px;
  margin-top: 8px;
  font-size: 11px;
  color: var(--t-tertiary);
}
.meta-item {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
</style>
