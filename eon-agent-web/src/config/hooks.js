/**
 * 钩子 → 中文名。钩子名来自后端 Hook#name()；当前只有压缩钩子会发 engine.hook 事件
 * （它是唯一会在无增量输出时同步调 LLM 的钩子），故表内只有一条。
 * 未在表中的直接显示原名，不会因缺少映射而报错。
 */
export const HOOK_LABEL = {
  ContextCompressionHook: '压缩上下文'
}

export function hookLabel(name) {
  return HOOK_LABEL[name] || name
}
