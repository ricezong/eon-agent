/** 通用格式化与工具函数。 */

/** 相对时间：刚刚 / x 分钟前 / 昨天 / MM-DD */
export function fromNow(input) {
  if (!input) return ''
  const t = typeof input === 'number' ? input : Date.parse(String(input).replace(' ', 'T'))
  if (Number.isNaN(t)) return ''
  const diff = Date.now() - t
  const min = 60000
  const hour = 3600000
  const day = 86400000
  if (diff < min) return '刚刚'
  if (diff < hour) return `${Math.floor(diff / min)} 分钟前`
  if (diff < day) return `${Math.floor(diff / hour)} 小时前`
  if (diff < 2 * day) return '昨天'
  if (diff < 7 * day) return `${Math.floor(diff / day)} 天前`
  const d = new Date(t)
  const p = (n) => String(n).padStart(2, '0')
  return `${p(d.getMonth() + 1)}-${p(d.getDate())}`
}

/** 会话列表分组标题：今天 / 昨天 / 更早 */
export function groupOf(input) {
  if (!input) return '更早'
  const t = typeof input === 'number' ? input : Date.parse(String(input).replace(' ', 'T'))
  if (Number.isNaN(t)) return '更早'
  const startOfToday = new Date()
  startOfToday.setHours(0, 0, 0, 0)
  if (t >= startOfToday.getTime()) return '今天'
  if (t >= startOfToday.getTime() - 86400000) return '昨天'
  return '更早'
}

/** 时钟时间 HH:mm */
export function clockTime(input) {
  const t = typeof input === 'number' ? input : Date.parse(String(input).replace(' ', 'T'))
  const d = Number.isNaN(t) ? new Date() : new Date(t)
  return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}

/** token 数格式化：1234 → 1.2k */
export function formatTokens(n) {
  const v = Number(n) || 0
  if (v < 1000) return String(v)
  if (v < 1000000) return `${(v / 1000).toFixed(v < 10000 ? 2 : 1).replace(/\.?0+$/, '')}k`
  return `${(v / 1000000).toFixed(2).replace(/\.?0+$/, '')}M`
}

/** 字节大小 */
export function formatBytes(input) {
  if (input == null) return ''
  if (typeof input === 'string' && Number.isNaN(Number(input))) return input
  const bytes = Number(input)
  if (!bytes || bytes < 0) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB']
  const i = Math.min(Math.floor(Math.log(bytes) / Math.log(1024)), units.length - 1)
  return `${(bytes / Math.pow(1024, i)).toFixed(i === 0 ? 0 : 1)} ${units[i]}`
}

/** 安全地解析 JSON 字符串（tool_use.input 是 JSON 文本） */
export function safeJson(text, fallback = null) {
  if (text == null || text === '') return fallback
  if (typeof text === 'object') return text
  try {
    return JSON.parse(text)
  } catch {
    return fallback
  }
}

/** 生成前端本地 ID */
export function uid(prefix = 'm') {
  return `${prefix}_${Date.now().toString(36)}_${Math.random().toString(36).slice(2, 8)}`
}

/**
 * 与后端 ChatServiceImpl#deriveTitle 保持一致：取用户输入前 10 个字符。
 * 用于新会话创建后，从会话列表中反查出后端生成的 sessionId。
 */
export function deriveTitle(text) {
  if (!text) return '新会话'
  return text.length <= 10 ? text : text.slice(0, 10)
}

/** 复制到剪贴板（带降级） */
export async function copyText(text) {
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(text)
      return true
    }
  } catch {
    /* 降级 */
  }
  try {
    const ta = document.createElement('textarea')
    ta.value = text
    ta.style.position = 'fixed'
    ta.style.opacity = '0'
    document.body.appendChild(ta)
    ta.select()
    const ok = document.execCommand('copy')
    document.body.removeChild(ta)
    return ok
  } catch {
    return false
  }
}
