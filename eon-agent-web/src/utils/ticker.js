/**
 * 打字机调度器。
 *
 * SSE 的 delta 到达节奏并不均匀（一次可能一大段），直接整段塞进 DOM 会失去「打字」手感。
 * 这里用一个全局 rAF 循环把「目标文本」逐帧推送到「已显示文本」，
 * 并带积压自适应：落后越多打得越快，保证不拖尾、也不会卡顿。
 */

const blocks = new Set()
let rafId = null
let lastTs = 0

let cps = 340 // 打字速度：字符/秒
const CATCHUP_RATIO = 0.1 // 每帧至少消化积压的比例（防拖尾）

/** 注册一个需要打字机效果的文本块（需含响应式 target / shown 字段）。 */
export function attach(block) {
  blocks.add(block)
  if (rafId == null) {
    lastTs = performance.now()
    rafId = requestAnimationFrame(tick)
  }
}

/** 注销文本块。 */
export function detach(block) {
  blocks.delete(block)
  if (!blocks.size && rafId != null) {
    cancelAnimationFrame(rafId)
    rafId = null
  }
}

/** 立即显示剩余全部内容。 */
export function flush(block) {
  block.shown = block.target
}

function tick(ts) {
  const dt = Math.min(ts - lastTs, 120)
  lastTs = ts

  for (const b of blocks) {
    const backlog = b.target.length - b.shown.length
    if (backlog <= 0) continue
    const step = Math.min(
      backlog,
      Math.max(1, Math.ceil((dt / 1000) * cps), Math.ceil(backlog * CATCHUP_RATIO))
    )
    b.shown = b.target.slice(0, b.shown.length + step)
  }

  rafId = blocks.size ? requestAnimationFrame(tick) : null
}

/** 打字速度（字符/秒），可在设置中调整。 */
export function setSpeed(value) {
  if (Number.isFinite(value) && value > 0) cps = value
}
