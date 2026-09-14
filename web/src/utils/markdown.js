/**
 * Markdown 渲染：marked(v15) + highlight.js + DOMPurify。
 * 流式场景下每帧只渲染已「打出」的部分，因此对速度做了优化（gfm、同步渲染）。
 */
import { marked } from 'marked'
import hljs from 'highlight.js/lib/common'
import DOMPurify from 'dompurify'

const renderer = new marked.Renderer()

function escapeHtml(str = '') {
  return String(str)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

/** 代码块：顶部带语言标签 + 复制按钮（点击由外层事件委托处理） */
renderer.code = function ({ text, lang }) {
  const raw = String(text ?? '').replace(/\n$/, '')
  const langName = (lang || '').match(/\S*/)?.[0] || ''
  let body
  try {
    body =
      langName && hljs.getLanguage(langName)
        ? hljs.highlight(raw, { language: langName, ignoreIllegals: true }).value
        : escapeHtml(raw)
  } catch {
    body = escapeHtml(raw)
  }
  return (
    `<pre class="md-code" data-lang="${escapeHtml(langName)}">` +
    `<div class="md-code__bar"><span class="md-code__lang">${escapeHtml(langName || 'text')}</span>` +
    `<button class="md-code__copy" type="button" data-copy>复制</button></div>` +
    `<code class="hljs">${body}</code></pre>`
  )
}

/** 外链新窗口打开，非法协议降级为 # */
renderer.link = function ({ href, title, tokens }) {
  const text = this.parser.parseInline(tokens)
  const safe = /^(https?:|mailto:|#|\/)/i.test(href || '') ? href : '#'
  const titleAttr = title ? ` title="${escapeHtml(title)}"` : ''
  return `<a href="${escapeHtml(safe)}" target="_blank" rel="noopener noreferrer"${titleAttr}>${text}</a>`
}

marked.use({ renderer, gfm: true, breaks: true, pedantic: false })

/**
 * 渲染 Markdown 为安全 HTML。
 * @param {string} src 源文本
 * @param {boolean} streaming 流式输出中：自动补全未闭合的代码围栏，避免闪烁
 */
export function renderMarkdown(src, streaming = false) {
  if (!src) return ''
  let text = String(src)
  if (streaming) {
    const fences = (text.match(/```/g) || []).length
    if (fences % 2 === 1) text += '\n```'
  }
  const html = marked.parse(text)
  return DOMPurify.sanitize(html, {
    ADD_ATTR: ['target', 'rel', 'data-lang', 'data-copy', 'class'],
    ADD_TAGS: ['button']
  })
}

export { hljs }
