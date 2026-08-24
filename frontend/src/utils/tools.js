/**
 * 工具元数据 - 后端 12 个内置工具 + MCP 工具的展示信息
 * icon 字段改为 Icon 组件的 name
 */

export const TOOL_META = {
  read_file:      { icon: 'file-text', label: '读取文件', color: '#06b6d4', category: '文件' },
  write:          { icon: 'pencil', label: '写入文件', color: '#f59e0b', category: '文件' },
  delete_file:    { icon: 'trash', label: '删除文件', color: '#ef4444', category: '文件' },
  list_dir:       { icon: 'folder', label: '浏览目录', color: '#06b6d4', category: '文件' },
  grep:           { icon: 'search', label: '搜索内容', color: '#8b5cf6', category: '文件' },
  download_file:  { icon: 'download', label: '下载文件', color: '#10b981', category: '网络' },
  web_fetch:      { icon: 'globe', label: '抓取网页', color: '#3b82f6', category: '网络' },
  web_search:     { icon: 'search-web', label: '网络搜索', color: '#6366f1', category: '网络' },
  todo_write:     { icon: 'list-checks', label: '任务管理', color: '#a855f7', category: '规划' },
  update_memory:  { icon: 'brain', label: '更新记忆', color: '#ec4899', category: '记忆' },
  AskQuestion:    { icon: 'help-circle', label: '询问用户', color: '#f59e0b', category: '交互' },
}

export function getToolMeta(name) {
  return TOOL_META[name] || { icon: 'wrench', label: name || '工具', color: '#7c7c92', category: '其他' }
}

export const EVENT_META = {
  RUN_START:    { icon: 'rocket', label: '启动', color: 'var(--c-primary)' },
  TURN_START:   { icon: 'refresh', label: '轮次', color: 'var(--c-info)' },
  LLM_RESPONSE: { icon: 'sparkles', label: '思考', color: 'var(--c-accent)' },
  TOOL_START:   { icon: 'zap', label: '工具', color: 'var(--c-warning)' },
  TOOL_RESULT:  { icon: 'check', label: '结果', color: 'var(--c-success)' },
  TURN_END:     { icon: 'check', label: '完成', color: 'var(--c-success)' },
  DONE:         { icon: 'check-circle', label: '完成', color: 'var(--c-success)' },
  TERMINATED:   { icon: 'ban', label: '终止', color: 'var(--c-danger)' },
  ERROR:        { icon: 'x-circle', label: '错误', color: 'var(--c-danger)' },
}
