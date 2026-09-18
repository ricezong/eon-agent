/**
 * 工具展示元数据（与后端 eon.tools.whitelist 及 MCP reader 服务对应）。
 * 对话流中只消费 label / icon / preview 三个字段：前两个画工具卡，
 * preview 声明该工具的结果要在消息流里单独成卡（file / web）。
 * 未登记的工具走 toolMeta() 的 fallback，不会因缺少元数据而报错。
 */

const TOOL_META = [
  { name: 'read_file', label: '读取文件', icon: 'fileText' },
  { name: 'write', label: '写入文件', icon: 'edit', preview: 'file' },
  { name: 'list_dir', label: '浏览目录', icon: 'folder' },
  { name: 'download_file', label: '下载文件', icon: 'download', preview: 'file' },
  { name: 'web_search', label: '联网搜索', icon: 'search' },
  { name: 'web_fetch', label: '抓取网页', icon: 'globe', preview: 'web' },
  { name: 'todo_write', label: '任务清单', icon: 'listChecks' },
  { name: 'update_memory', label: '记忆管理', icon: 'bulb' },
  { name: 'ask_question', label: '向你提问', icon: 'help' },
  { name: 'reader_search_novel', label: '搜索小说', icon: 'book' },
  { name: 'reader_search_comic', label: '搜索漫画', icon: 'image' },
  { name: 'reader_book_info', label: '作品详情', icon: 'book' },
  { name: 'reader_chapters', label: '章节目录', icon: 'layers' },
  { name: 'reader_content', label: '章节正文', icon: 'fileText' },
  { name: 'reader_download', label: '批量下载', icon: 'download' },
  { name: 'reader_list_sources', label: '书源列表', icon: 'database' }
]

const META_MAP = new Map(TOOL_META.map((t) => [t.name, t]))

/** 取工具元数据，未登记的工具回退为通用图标。 */
export function toolMeta(name) {
  if (!name) return { name: 'unknown', label: '未知工具', icon: 'plug' }
  const hit = META_MAP.get(name)
  if (hit) return hit
  // MCP 远程工具按前缀给个可读名
  if (name.startsWith('reader_')) {
    return { name, label: name.replace('reader_', '').replace(/_/g, ' '), icon: 'book' }
  }
  return { name, label: name, icon: 'plug' }
}
