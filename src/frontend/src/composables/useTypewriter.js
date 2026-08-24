import { ref } from 'vue'

/**
 * 打字机效果 composable
 * 逐字渲染文本，支持中途追加新内容、跳过完成
 */
export function useTypewriter(opts = {}) {
  const speed = opts.speed || 18 // ms per char
  const fastSpeed = opts.fastSpeed || 3

  const displayed = ref('')
  const typing = ref(false)
  const fullText = ref('')

  let timer = null
  let charIndex = 0
  let skip = false

  function type(target) {
    fullText.value = target
    if (timer) clearInterval(timer)
    typing.value = true
    skip = false
    charIndex = Math.min(charIndex, target.length)

    timer = setInterval(() => {
      if (skip || charIndex >= target.length) {
        clearInterval(timer)
        timer = null
        typing.value = false
        displayed.value = target
        return
      }
      // 每次推进 1-3 字符，模拟自然节奏
      const step = Math.random() < 0.3 ? 2 : 1
      charIndex = Math.min(charIndex + step, target.length)
      displayed.value = target.slice(0, charIndex)
    }, skip ? fastSpeed : speed)
  }

  /** 追加内容（用于流式增量更新） */
  function append(text) {
    fullText.value += text
    type(fullText.value)
  }

  /** 直接设置完整文本并开始打字 */
  function set(text) {
    fullText.value = text
    charIndex = 0
    displayed.value = ''
    type(text)
  }

  /** 跳过打字，直接显示全部 */
  function finish() {
    skip = true
    if (timer) {
      clearInterval(timer)
      timer = null
    }
    typing.value = false
    displayed.value = fullText.value
  }

  /** 重置 */
  function reset() {
    if (timer) clearInterval(timer)
    timer = null
    typing.value = false
    displayed.value = ''
    fullText.value = ''
    charIndex = 0
    skip = false
  }

  return { displayed, typing, set, append, finish, reset }
}
