import { ref, onBeforeUnmount } from 'vue'

/**
 * 打字机效果 composable（参考 frontend_old typewriter 引擎）
 *
 * 核心设计：
 * 1. Buffer 累积 + 定时消费，与内容到达方式解耦
 *    - 支持一次性 setBuffer（DONE 事件全量到达）
 *    - 支持增量 appendBuffer（流式 content 事件，预留）
 * 2. 自适应步长：根据剩余 buffer 长度动态调整每 tick 吐字数
 *    - buffer < 40 字 → step 1（慢速，自然节奏）
 *    - buffer 40~640 字 → step 1~16（逐渐加速）
 *    - buffer > 640 字 → step 16（快速消化长文本，避免等待过久）
 * 3. 16ms interval（~60fps），流畅无卡顿
 * 4. finish() 可立即跳到末尾（点击跳过）
 */
export function useTypewriter() {
  const displayed = ref('')
  const typing = ref(false)

  let buffer = ''
  let timer = null

  function tick() {
    if (buffer.length === 0) {
      if (timer) {
        clearInterval(timer)
        timer = null
      }
      typing.value = false
      return
    }

    // 自适应步长：min(max(ceil(bufLen/40), 1), 16)
    const step = Math.min(Math.max(Math.ceil(buffer.length / 40), 1), 16)
    displayed.value += buffer.slice(0, step)
    buffer = buffer.slice(step)
  }

  /**
   * 设置完整文本并开始打字
   * 用于 DONE 事件全量到达的场景
   */
  function setBuffer(text) {
    buffer = text
    displayed.value = ''
    if (timer) {
      clearInterval(timer)
      timer = null
    }
    typing.value = true
    timer = setInterval(tick, 16)
    // 立即消费第一段，避免首帧空白
    tick()
  }

  /**
   * 追加内容到 buffer（流式增量预留）
   * 用于后端未来支持增量 content 事件时
   */
  function appendBuffer(text) {
    buffer += text
    if (!timer) {
      typing.value = true
      timer = setInterval(tick, 16)
    }
  }

  /**
   * 跳过打字，直接显示全部
   */
  function finish() {
    if (timer) {
      clearInterval(timer)
      timer = null
    }
    if (buffer) {
      displayed.value += buffer
      buffer = ''
    }
    typing.value = false
  }

  /**
   * 重置全部状态
   */
  function reset() {
    if (timer) {
      clearInterval(timer)
      timer = null
    }
    buffer = ''
    displayed.value = ''
    typing.value = false
  }

  onBeforeUnmount(() => {
    if (timer) clearInterval(timer)
  })

  return { displayed, typing, setBuffer, appendBuffer, finish, reset }
}
