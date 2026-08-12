package cn.kong.eon.agent.profile;

/**
 * 请求 Profile（由 PolicyRouter 分配）。
 *
 * LIGHT_CHAT：简单聊天，不注入工具 Schema，不激活 Agent 层
 * ASSISTED：辅助查询，注入工具目录摘要，LLM 按需加载完整 Schema
 * TASK_MULTI：多步任务，全量工具 Schema + Navigator + 门禁
 */
public enum RequestProfile {
    LIGHT_CHAT,
    ASSISTED,
    TASK_MULTI
}
