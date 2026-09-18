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

/** crypto.randomUUID 只在安全上下文可用（https 或 localhost），其余环境回退到时间戳 + 随机串。 */
function randomId() {
  if (globalThis.crypto?.randomUUID) return crypto.randomUUID()
  return Date.now().toString(36) + Math.random().toString(36).slice(2, 10)
}

/**
 * 匿名设备标识：首访生成并固化。后端按它隔离会话索引，
 * 清站点数据 / 换浏览器都会产生新 id——旧会话仍在磁盘上，只是不再出现在列表里。
 */
function ensureUserId() {
  if (saved.userId) return saved.userId
  saved.userId = 'u-' + randomId()
  localStorage.setItem(KEY, JSON.stringify(saved))
  return saved.userId
}

export const useSettingsStore = defineStore('settings', () => {
  /** 后端按 userId 隔离会话索引；不填时后端回落到 default */
  const userId = ref(ensureUserId())
  /** 打字机速度（字符/秒） */
  const typeSpeed = ref(saved.typeSpeed || 340)
  /** 流式输出时是否自动滚动到底部 */
  const autoScroll = ref(saved.autoScroll !== false)
  /** 是否默认展开思考过程 */
  const expandThinking = ref(saved.expandThinking === true)
  /** 工具结果默认折叠 */
  const collapseToolResult = ref(saved.collapseToolResult !== false)
  /** HTML 预览是否执行脚本：关闭后沙箱内不运行 JS */
  const htmlPreviewScripts = ref(saved.htmlPreviewScripts !== false)

  setSpeed(typeSpeed.value)

  watch([userId, typeSpeed, autoScroll, expandThinking, collapseToolResult, htmlPreviewScripts], () => {
    localStorage.setItem(
      KEY,
      JSON.stringify({
        userId: userId.value,
        typeSpeed: typeSpeed.value,
        autoScroll: autoScroll.value,
        expandThinking: expandThinking.value,
        collapseToolResult: collapseToolResult.value,
        htmlPreviewScripts: htmlPreviewScripts.value
      })
    )
  })

  watch(typeSpeed, (v) => setSpeed(v))

  return { userId, typeSpeed, autoScroll, expandThinking, collapseToolResult, htmlPreviewScripts }
})
