package cn.kong.eon.agent.profile;

/**
 * 请求 Profile。
 *   SIMPLE — 默认模式，注入 tool_catalog 摘要，不挂载完整 Schema（两阶段懒加载）。
 *   TASK   — todo_write 后升级，全量挂载工具 Schema，TodoNavigator 激活。
 */
public enum RequestProfile {
    SIMPLE,
    TASK
}
