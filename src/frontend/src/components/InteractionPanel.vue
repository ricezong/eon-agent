<script setup>
import { ref, watch, onMounted } from 'vue'
import { store, showToast } from '../stores/app'
import { api } from '../api'
import Icon from './Icon.vue'

const props = defineProps({
  sessionId: { type: String, required: true },
})

const interaction = ref(null)
const answers = ref({})
const submitting = ref(false)

async function checkInteraction() {
  if (!props.sessionId) return
  try {
    const resp = await api.getInteraction(props.sessionId)
    if (resp && resp.pending) {
      interaction.value = resp
      answers.value = {}
    } else {
      interaction.value = null
    }
  } catch (e) {
    // 静默失败
  }
}

// 轮询检查交互状态（Agent 运行期间）
let pollTimer = null
function startPolling() {
  stopPolling()
  pollTimer = setInterval(checkInteraction, 1500)
}
function stopPolling() {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

watch(
  () => store.running,
  (running) => {
    if (running) startPolling()
    else {
      stopPolling()
      checkInteraction()
    }
  }
)

watch(() => props.sessionId, () => {
  interaction.value = null
  checkInteraction()
})

onMounted(() => {
  if (store.running) startPolling()
  checkInteraction()
})

function toggleOption(qId, optionId, allowMultiple) {
  if (allowMultiple) {
    const cur = (answers.value[qId] || '').split(',').filter(Boolean)
    const idx = cur.indexOf(optionId)
    if (idx >= 0) cur.splice(idx, 1)
    else cur.push(optionId)
    answers.value[qId] = cur.join(',')
  } else {
    answers.value[qId] = optionId
  }
}

function isSelected(qId, optionId) {
  const cur = answers.value[qId] || ''
  return cur.split(',').includes(optionId)
}

async function submit() {
  const questions = interaction.value?.questions || []
  for (const q of questions) {
    if (!answers.value[q.id]) {
      showToast(`请回答问题：${q.prompt || q.id}`, 'warning')
      return
    }
  }
  submitting.value = true
  try {
    await api.submitAnswer(props.sessionId, { ...answers.value })
    showToast('答案已提交，Agent 正在恢复执行', 'success')
    interaction.value = null
    answers.value = {}
  } catch (e) {
    showToast('提交失败：' + e.message, 'error')
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div v-if="interaction" class="interaction-panel">
    <div class="panel-header">
      <span class="panel-icon"><Icon name="help-circle" :size="22" /></span>
      <div>
        <div class="panel-title">{{ interaction.title || '需要你的输入' }}</div>
        <div class="panel-sub">Agent 暂停等待答案</div>
      </div>
    </div>

    <div v-for="(q, idx) in (interaction.questions || [])" :key="q.id || idx" class="question">
      <div class="q-prompt">{{ idx + 1 }}. {{ q.prompt || q.id }}</div>
      <div v-if="q.description" class="q-desc">{{ q.description }}</div>
      <div class="q-options">
        <button
          v-for="opt in (q.options || [])"
          :key="opt.id"
          class="q-option"
          :class="{ selected: isSelected(q.id, opt.id) }"
          @click="toggleOption(q.id, opt.id, q.allow_multiple)"
        >
          <span class="opt-radio"></span>
          <span>{{ opt.label || opt.id }}</span>
        </button>
      </div>
    </div>

    <button class="submit-btn" :disabled="submitting" @click="submit">
      {{ submitting ? '提交中…' : '提交答案' }}
    </button>
  </div>
</template>

<style scoped>
.interaction-panel {
  margin: 12px 0;
  padding: 16px;
  background: var(--grad-primary-soft);
  border: 1px solid var(--border-primary);
  border-radius: var(--r-lg);
  animation: slideIn 0.3s ease;
}
@keyframes slideIn {
  from { opacity: 0; transform: translateY(-8px); }
  to { opacity: 1; transform: translateY(0); }
}

.panel-header {
  display: flex;
  gap: 12px;
  align-items: center;
  margin-bottom: 14px;
  padding-bottom: 12px;
  border-bottom: 1px solid var(--border-default);
}
.panel-icon {
  font-size: 24px;
}
.panel-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--t-primary);
}
.panel-sub {
  font-size: 11px;
  color: var(--t-tertiary);
  margin-top: 2px;
}

.question {
  margin: 12px 0;
}
.q-prompt {
  font-size: 13px;
  font-weight: 500;
  color: var(--t-primary);
  margin-bottom: 4px;
}
.q-desc {
  font-size: 12px;
  color: var(--t-tertiary);
  margin-bottom: 8px;
}
.q-options {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.q-option {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 12px;
  background: var(--bg-surface);
  border: 1px solid var(--border-default);
  border-radius: var(--r-md);
  color: var(--t-secondary);
  font-size: 13px;
  cursor: pointer;
  text-align: left;
  transition: all var(--dur-fast);
}
.q-option:hover {
  border-color: var(--border-primary);
  background: var(--bg-surface-hover);
}
.q-option.selected {
  border-color: var(--c-primary);
  background: rgba(99, 102, 241, 0.12);
  color: var(--t-primary);
}
.opt-radio {
  width: 14px;
  height: 14px;
  border-radius: 50%;
  border: 2px solid var(--t-tertiary);
  flex-shrink: 0;
  transition: all var(--dur-fast);
}
.q-option.selected .opt-radio {
  border-color: var(--c-primary);
  background: var(--c-primary);
  box-shadow: inset 0 0 0 3px var(--bg-surface);
}

.submit-btn {
  margin-top: 14px;
  width: 100%;
  padding: 10px;
  border: none;
  border-radius: var(--r-md);
  background: var(--grad-primary);
  color: white;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition: all var(--dur-fast);
}
.submit-btn:hover:not(:disabled) {
  box-shadow: 0 4px 16px rgba(99, 102, 241, 0.4);
}
.submit-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
</style>
