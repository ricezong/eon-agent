/**
 * 全局设置：用户标识、打字机速度等，持久化到 localStorage。
 */
import { defineStore } from 'pinia'
import { ref, watch } from 'vue'
import { setSpeed } from '@/utils/ticker'

const KEY = 'eon-agent-settings'

function load() {
  try {
    return JSON.parse(localStorage.getItem(KEY) || '{}')
  } catch {
    return {}
  }
}

const saved = load()

export const useSettingsStore = defineStore('settings', () => {
  /** 后端按 userId 隔离会话索引，默认与后端 defaultValue 一致 */
  const userId = ref(saved.userId || 'default')
  /** 打字机速度（字符/秒） */
  const typeSpeed = ref(saved.typeSpeed || 340)
  /** 流式输出时是否自动滚动到底部 */
  const autoScroll = ref(saved.autoScroll !== false)
  /** 是否默认展开思考过程 */
  const expandThinking = ref(saved.expandThinking === true)
  /** 工具结果默认折叠 */
  const collapseToolResult = ref(saved.collapseToolResult !== false)

  setSpeed(typeSpeed.value)

  watch([userId, typeSpeed, autoScroll, expandThinking, collapseToolResult], () => {
    localStorage.setItem(
      KEY,
      JSON.stringify({
        userId: userId.value,
        typeSpeed: typeSpeed.value,
        autoScroll: autoScroll.value,
        expandThinking: expandThinking.value,
        collapseToolResult: collapseToolResult.value
      })
    )
  })

  watch(typeSpeed, (v) => setSpeed(v))

  function reset() {
    userId.value = 'default'
    typeSpeed.value = 340
    autoScroll.value = true
    expandThinking.value = false
    collapseToolResult.value = true
  }

  return { userId, typeSpeed, autoScroll, expandThinking, collapseToolResult, reset }
})
