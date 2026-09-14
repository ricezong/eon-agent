<script setup>
import { computed, reactive, ref } from 'vue'
import AppIcon from '@/components/common/AppIcon.vue'
import { useSessionStore } from '@/stores/session'

/**
 * 提问卡片。一次只渲染一题，答完点「下一题」进入下一题，最后一题点「提交答案」一次性提交全部答案；
 * 「上一题」可回退修改，已填内容保留。
 * 答案直接投递给阻塞中的那次 ask_question，本轮任务不中断；等待期间输入框不可用。
 */
const props = defineProps({
  question: { type: Object, required: true }
})

const session = useSessionStore()

const OTHER = '__other__'
const submitting = ref(false)
const step = ref(0)

/** questionId → { ids: [], other: '' }，预先初始化，模板里直接按下标访问 */
const picked = reactive({})

for (const q of props.question.questions) {
  picked[q.id] = { ids: [], other: '' }
}

const total = computed(() => props.question.questions.length)
const current = computed(() => props.question.questions[step.value])
const isLast = computed(() => step.value === total.value - 1)
/** 当前题的已选状态 */
const cur = computed(() => picked[current.value.id])
/** 「其他」输入框是否展开由已选项推导，不单独存状态 */
const otherOpen = computed(() => cur.value.ids.includes(OTHER))

function isOn(optionId) {
  return cur.value.ids.includes(optionId)
}

function isOtherOn() {
  return isOn(OTHER)
}

function toggle(optionId) {
  const st = cur.value
  if (current.value.allowMultiple) {
    st.ids = st.ids.includes(optionId) ? st.ids.filter((x) => x !== optionId) : [...st.ids, optionId]
  } else {
    st.ids = st.ids[0] === optionId ? [] : [optionId]
  }
}

/** 「其他」被选中但还没填内容时不算完成 */
function isFilled(q) {
  const st = picked[q.id]
  if (!st.ids.length) return false
  return !st.ids.includes(OTHER) || st.other.trim().length > 0
}

const currentFilled = computed(() => isFilled(current.value))
const allFilled = computed(() => props.question.questions.every(isFilled))
const answeredCount = computed(() => props.question.questions.filter(isFilled).length)

/** 选项序号，从 1 起；「其他」延续同一序列 */
const noOf = (i) => i + 1
const otherNo = (q) => q.options.length + 1

function prev() {
  if (step.value > 0) step.value -= 1
}

/** 回车：非最后一题等同于「下一题」，最后一题直接提交 */
function advance() {
  if (!currentFilled.value) return
  if (isLast.value) submit()
  else step.value += 1
}

async function submit() {
  if (!allFilled.value || submitting.value) return
  submitting.value = true
  const answers = {}
  for (const q of props.question.questions) {
    const st = picked[q.id]
    const labels = st.ids
      .filter((id) => id !== OTHER)
      .map((id) => q.options.find((o) => o.id === id)?.label)
      .filter(Boolean)
    answers[q.id] = { labels, other: st.ids.includes(OTHER) ? st.other.trim() : '' }
  }
  try {
    await session.answer(answers)
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="qc">
    <header class="qc__head">
      <span class="qc__icon">
        <AppIcon name="help" :size="15" />
      </span>
      <div class="qc__title">
        <div class="qc__name">{{ question.title || '需要确认几件事' }}</div>
        <div class="qc__sub">
          第 {{ step + 1 }} / {{ total }} 题 · 已答 {{ answeredCount }} / {{ total }} · 回答后任务继续执行
        </div>
      </div>
      <div v-if="total > 1" class="qc__dots">
        <span
          v-for="(q, qi) in question.questions"
          :key="q.id"
          class="qc__dot"
          :class="{ 'is-on': qi === step, 'is-done': qi !== step && isFilled(q) }"
        />
      </div>
    </header>

    <div class="qc__body">
      <section class="qc__q">
        <div class="qc__qhead">
          <span class="qc__qno">{{ step + 1 }}</span>
          <span class="qc__prompt">{{ current.prompt }}</span>
          <span v-if="current.allowMultiple" class="qc__tag">可多选</span>
        </div>

        <div class="qc__opts">
          <button
            v-for="(o, oi) in current.options"
            :key="o.id"
            class="qb"
            :class="{ 'is-on': isOn(o.id), 'is-multi': current.allowMultiple }"
            @click="toggle(o.id)"
          >
            <span class="qb__no">{{ noOf(oi) }}</span>
            <span class="qb__label">{{ o.label }}</span>
            <AppIcon v-if="isOn(o.id)" name="check" :size="13" class="qb__tick" />
          </button>

          <button
            class="qb qb--other"
            :class="{ 'is-on': isOtherOn(), 'is-multi': current.allowMultiple }"
            @click="toggle(OTHER)"
          >
            <span class="qb__no">{{ otherNo(current) }}</span>
            <span class="qb__label">其他…</span>
          </button>

          <input
            v-if="otherOpen"
            v-model="cur.other"
            class="qc__input"
            type="text"
            placeholder="输入你的答案"
            @keyup.enter="advance"
          />
        </div>
      </section>
    </div>

    <footer class="qc__foot">
      <button v-if="step > 0" class="qc__ghost" :disabled="submitting" @click="prev">上一题</button>
      <span class="qc__spacer" />
      <button v-if="!isLast" class="qc__submit" :disabled="!currentFilled" @click="step += 1">
        下一题
      </button>
      <button v-else class="qc__submit" :disabled="!allFilled || submitting" @click="submit">
        {{ submitting ? '提交中…' : '提交答案' }}
      </button>
    </footer>
  </div>
</template>

<style scoped>
.qc {
  border: 1px solid rgba(139, 92, 246, 0.3);
  border-radius: var(--r-md);
  background: rgba(139, 92, 246, 0.055);
  overflow: hidden;
}

.qc__head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 12px;
}
.qc__icon {
  width: 26px;
  height: 26px;
  border-radius: 8px;
  display: grid;
  place-items: center;
  background: var(--grad-soft);
  color: #c4b5fd;
  flex: none;
}
.qc__title {
  min-width: 0;
  flex: 1;
}
.qc__name {
  font-size: 13px;
  font-weight: 500;
}
.qc__sub {
  font-size: 11px;
  color: var(--text-muted);
}

.qc__dots {
  display: flex;
  gap: 4px;
  flex: none;
}
.qc__dot {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: var(--border-strong);
  transition: background 0.16s, transform 0.16s;
}
.qc__dot.is-done {
  background: rgba(139, 92, 246, 0.5);
}
.qc__dot.is-on {
  background: var(--grad-brand);
  transform: scale(1.35);
}

.qc__body {
  padding: 0 12px;
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.qc__qhead {
  display: flex;
  align-items: baseline;
  gap: 7px;
  margin-bottom: 7px;
}
.qc__qno {
  font-size: 12px;
  font-weight: 500;
  color: #a5b4fc;
  font-variant-numeric: tabular-nums;
  flex: none;
}
.qc__prompt {
  font-size: 13px;
  color: var(--text);
  min-width: 0;
}
.qc__tag {
  margin-left: auto;
  flex: none;
  font-size: 10.5px;
  color: #a5b4fc;
  border: 1px solid rgba(139, 92, 246, 0.3);
  border-radius: 99px;
  padding: 1px 7px;
}

.qc__opts {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

/* 选项：整行按钮 + 序号徽章，纵向排列成问卷样式 */
.qb {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 7px 10px;
  border: 1px solid var(--border);
  border-radius: 10px;
  background: rgba(255, 255, 255, 0.04);
  color: var(--text-soft);
  font-size: 12.8px;
  text-align: left;
  cursor: pointer;
  transition: border-color 0.16s, background 0.16s, color 0.16s;
}
.qb:hover {
  border-color: var(--border-strong);
  background: rgba(255, 255, 255, 0.07);
  color: var(--text);
}
.qb.is-on {
  border-color: rgba(139, 92, 246, 0.55);
  background: rgba(139, 92, 246, 0.16);
  color: var(--text);
}

.qb__no {
  width: 21px;
  height: 21px;
  border-radius: 50%;
  border: 1px solid var(--border-strong);
  display: grid;
  place-items: center;
  font-size: 11.5px;
  color: var(--text-muted);
  font-variant-numeric: tabular-nums;
  flex: none;
  transition: background 0.16s, color 0.16s, border-color 0.16s;
}
/* 多选改用方角徽章，一眼区分单/多选 */
.qb.is-multi .qb__no {
  border-radius: 6px;
}
.qb.is-on .qb__no {
  background: var(--grad-brand);
  border-color: transparent;
  color: #fff;
}

.qb__label {
  min-width: 0;
  flex: 1;
  line-height: 1.45;
}
.qb__tick {
  color: #a5b4fc;
  flex: none;
}

.qb--other {
  border-style: dashed;
}

.qc__input {
  margin-top: 2px;
  width: 100%;
  border: 1px solid var(--border);
  background: rgba(0, 0, 0, 0.25);
  border-radius: 9px;
  padding: 7px 11px;
  font-size: 12.8px;
  color: var(--text);
  outline: none;
}
.qc__input:focus {
  border-color: rgba(139, 92, 246, 0.5);
}

.qc__foot {
  padding: 12px;
  display: flex;
  align-items: center;
  gap: 8px;
}
.qc__spacer {
  flex: 1;
}
.qc__submit {
  border: none;
  border-radius: 9px;
  padding: 7px 18px;
  font-size: 12.8px;
  cursor: pointer;
  background: var(--grad-brand);
  color: #fff;
  transition: opacity 0.16s, transform 0.16s;
}
.qc__submit:hover:not(:disabled) {
  transform: translateY(-1px);
}
.qc__submit:disabled {
  opacity: 0.42;
  cursor: not-allowed;
}
.qc__ghost {
  border: 1px solid var(--border);
  border-radius: 9px;
  padding: 6px 14px;
  font-size: 12.8px;
  cursor: pointer;
  background: transparent;
  color: var(--text-soft);
  transition: border-color 0.16s, color 0.16s;
}
.qc__ghost:hover:not(:disabled) {
  border-color: var(--border-strong);
  color: var(--text);
}
.qc__ghost:disabled {
  opacity: 0.42;
  cursor: not-allowed;
}
</style>
