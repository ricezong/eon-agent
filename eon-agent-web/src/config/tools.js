/**
 * 工具能力元数据（与后端 eon.tools.whitelist 及 MCP reader 服务对应）。
 * 用于「能力矩阵」页面与对话流中工具卡片的图标/配色/中文名展示。
 * 运行时出现的未知工具会走 fallback 渲染，不会因缺少元数据而报错。
 */

export const TOOL_META = [
  {
    name: 'read_file',
    label: '读取文件',
    category: '文件',
    icon: 'fileText',
    source: 'local',
    desc: '读取工作目录下的文本文件内容，支持按行区间截取与 artifact:// 引用。',
    params: ['target_file', 'offset', 'limit']
  },
  {
    name: 'write',
    label: '写入文件',
    category: '文件',
    icon: 'edit',
    source: 'local',
    desc: '创建或覆盖写入文件，路径相对于会话工作目录。',
    params: ['file_path', 'contents']
  },
  {
    name: 'list_dir',
    label: '浏览目录',
    category: '文件',
    icon: 'folder',
    source: 'local',
    desc: '列出指定目录的文件与子目录结构，不传路径时浏览工作目录本身。',
    params: ['target_directory']
  },
  {
    name: 'download_file',
    label: '下载文件',
    category: '文件',
    icon: 'download',
    source: 'local',
    desc: '把网络文件下载到会话目录，单文件上限 100 MB。',
    params: ['url', 'file_name']
  },
  {
    name: 'web_search',
    label: '联网搜索',
    category: '信息',
    icon: 'search',
    source: 'local',
    desc: '调用百度千帆 AI Search 检索实时信息，支持站点过滤与时间范围过滤。',
    params: ['query', 'max_results', 'site_filter', 'recency_filter']
  },
  {
    name: 'web_fetch',
    label: '抓取网页',
    category: '信息',
    icon: 'globe',
    source: 'local',
    desc: '批量抓取 URL 内容并转为可读文本，单页截断 50000 字符，带 15 分钟缓存。',
    params: ['urls']
  },
  {
    name: 'todo_write',
    label: '任务清单',
    category: '规划',
    icon: 'listChecks',
    source: 'local',
    desc: '创建与维护待办列表，复杂任务自动拆解并跟踪进度（pending / in_progress / completed）。',
    params: ['todos', 'merge']
  },
  {
    name: 'update_memory',
    label: '记忆管理',
    category: '规划',
    icon: 'bulb',
    source: 'local',
    desc: '持久化存储重要事实与偏好，跨会话复用（create / update / delete）。',
    params: ['action', 'title', 'knowledge_to_store', 'existing_knowledge_id']
  },
  {
    name: 'ask_question',
    label: '向你提问',
    category: '交互',
    icon: 'help',
    source: 'local',
    desc: '在执行过程中向你确认选项，支持单选/多选的表单式提问。',
    params: ['questions', 'title']
  },
  {
    name: 'reader_search_novel',
    label: '搜索小说',
    category: '阅读',
    icon: 'book',
    source: 'mcp',
    desc: '按书名/作者搜索小说作品，返回作品与来源站点信息。',
    params: ['keyword', 'source']
  },
  {
    name: 'reader_search_comic',
    label: '搜索漫画',
    category: '阅读',
    icon: 'image',
    source: 'mcp',
    desc: '按名称/作者搜索漫画作品。',
    params: ['keyword', 'source']
  },
  {
    name: 'reader_book_info',
    label: '作品详情',
    category: '阅读',
    icon: 'book',
    source: 'mcp',
    desc: '获取书籍详情信息（作者、简介、状态等）。',
    params: ['book_id']
  },
  {
    name: 'reader_chapters',
    label: '章节目录',
    category: '阅读',
    icon: 'layers',
    source: 'mcp',
    desc: '获取书籍的完整章节目录列表。',
    params: ['book_id']
  },
  {
    name: 'reader_content',
    label: '章节正文',
    category: '阅读',
    icon: 'fileText',
    source: 'mcp',
    desc: '抓取指定章节的正文内容。',
    params: ['chapter_id']
  },
  {
    name: 'reader_download',
    label: '批量下载',
    category: '阅读',
    icon: 'download',
    source: 'mcp',
    desc: '批量下载章节并保存为本地 TXT/HTML 文件。',
    params: ['book_id', 'chapters']
  },
  {
    name: 'reader_list_sources',
    label: '书源列表',
    category: '阅读',
    icon: 'database',
    source: 'mcp',
    desc: '列出可用的小说/漫画来源站点。',
    params: []
  }
]

const META_MAP = new Map(TOOL_META.map((t) => [t.name, t]))

/** 分类配色 */
export const CATEGORY_STYLE = {
  文件: { icon: 'fileText', grad: 'linear-gradient(135deg,#6366f1,#22d3ee)' },
  信息: { icon: 'globe', grad: 'linear-gradient(135deg,#0ea5e9,#22d3ee)' },
  规划: { icon: 'listChecks', grad: 'linear-gradient(135deg,#8b5cf6,#f472b6)' },
  交互: { icon: 'help', grad: 'linear-gradient(135deg,#f59e0b,#f472b6)' },
  阅读: { icon: 'book', grad: 'linear-gradient(135deg,#10b981,#22d3ee)' },
  其他: { icon: 'plug', grad: 'linear-gradient(135deg,#64748b,#94a3b8)' }
}

/** 取工具元数据，未知工具回退为通用描述。 */
export function toolMeta(name) {
  if (!name) return { name: 'unknown', label: '未知工具', category: '其他', icon: 'plug', source: 'unknown', desc: '', params: [] }
  const hit = META_MAP.get(name)
  if (hit) return hit
  // MCP 远程工具按前缀猜测分类
  if (name.startsWith('reader_')) {
    return { name, label: name.replace('reader_', '').replace(/_/g, ' '), category: '阅读', icon: 'book', source: 'mcp', desc: 'MCP 远程工具', params: [] }
  }
  return { name, label: name, category: '其他', icon: 'plug', source: 'mcp', desc: 'MCP / 远程工具', params: [] }
}

/** 按分类分组 */
export function toolsByCategory() {
  const groups = new Map()
  for (const t of TOOL_META) {
    if (!groups.has(t.category)) groups.set(t.category, [])
    groups.get(t.category).push(t)
  }
  return [...groups.entries()].map(([category, items]) => ({
    category,
    items,
    style: CATEGORY_STYLE[category] || CATEGORY_STYLE.其他
  }))
}
