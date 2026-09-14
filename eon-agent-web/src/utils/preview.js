/**
 * 工具结果预览的类型判定。
 * 后端只交付事实（mime / sizeBytes / binary / encoding），「能不能预览、用什么渲染」在此集中定义，
 * 避免判定逻辑散落到各个组件里。
 */

/** 扩展名 → 渲染器类型 */
const EXT_KIND = {
  html: 'html', htm: 'html', xhtml: 'html', vue: 'html',
  md: 'markdown', markdown: 'markdown', mdx: 'markdown',
  png: 'image', jpg: 'image', jpeg: 'image', gif: 'image',
  webp: 'image', bmp: 'image', ico: 'image', svg: 'image',
  pdf: 'pdf',
  js: 'code', mjs: 'code', ts: 'code', jsx: 'code', tsx: 'code',
  json: 'code', css: 'code', scss: 'code', less: 'code',
  xml: 'code', yml: 'code', yaml: 'code', toml: 'code', ini: 'code',
  py: 'code', java: 'code', kt: 'code', go: 'code', rs: 'code',
  c: 'code', h: 'code', cpp: 'code', cs: 'code', php: 'code',
  rb: 'code', swift: 'code', sql: 'code', sh: 'code', bat: 'code',
  txt: 'text', log: 'text', csv: 'text', env: 'text'
}

/** 扩展名 → highlight.js 语言；未列出的交给 hljs 自动识别 */
const EXT_LANG = {
  js: 'javascript', mjs: 'javascript', jsx: 'javascript',
  ts: 'typescript', tsx: 'typescript',
  py: 'python', rb: 'ruby', sh: 'bash', bat: 'bash',
  yml: 'yaml', md: 'markdown', vue: 'xml', svg: 'xml',
  htm: 'html', c: 'c', h: 'c', rs: 'rust', kt: 'kotlin'
}

/** 超过此体积不自动预览，只提供下载（后端文本预览上限 200KB） */
export const PREVIEW_MAX_BYTES = 512 * 1024

/** 取扩展名（小写，不含点） */
function extOf(name = '') {
  const i = String(name).lastIndexOf('.')
  return i > 0 && i < name.length - 1 ? name.slice(i + 1).toLowerCase() : ''
}

/** 取文件名（兼容 / 与 \\ 两种分隔符） */
export function baseName(path = '') {
  const s = String(path).replace(/\\/g, '/')
  return s.slice(s.lastIndexOf('/') + 1)
}

/**
 * 判定渲染器类型。
 * @param {{name?:string, mime?:string, binary?:boolean}} meta
 * @returns {'html'|'markdown'|'image'|'pdf'|'code'|'text'|'binary'}
 */
export function kindOf(meta = {}) {
  const name = meta.name || baseName(meta.path || '')
  const mime = (meta.mime || '').toLowerCase()
  const ext = extOf(name)

  if (mime.startsWith('image/')) return 'image'
  if (mime === 'application/pdf' || ext === 'pdf') return 'pdf'
  if (meta.binary) return 'binary'

  const byExt = EXT_KIND[ext]
  if (byExt) return byExt
  if (mime.startsWith('text/')) return ext ? 'code' : 'text'
  if (mime.includes('json') || mime.includes('xml') || mime.includes('javascript')) return 'code'
  return 'text'
}

/** 代码块语言，返回空串表示交给 hljs 自动识别 */
export function langOf(name = '') {
  return EXT_LANG[extOf(name)] || ''
}

/** 该类型是否能渲染出内容（不能渲染的只提供下载） */
export function isRenderable(kind) {
  return kind !== 'binary'
}

/** 是否需要向后端取文本内容（图片 / PDF 直接走 raw 链接） */
export function needsText(kind) {
  return kind === 'html' || kind === 'markdown' || kind === 'code' || kind === 'text'
}

/** 只允许 http(s)，杜绝 javascript: 之类的协议 */
export function safeUrl(url) {
  const u = String(url || '').trim()
  return /^https?:\/\//i.test(u) ? u : ''
}

/** 取站点域名，用于 favicon 缺失时的占位与展示 */
export function hostOf(url) {
  try {
    return new URL(safeUrl(url)).host
  } catch {
    return ''
  }
}
