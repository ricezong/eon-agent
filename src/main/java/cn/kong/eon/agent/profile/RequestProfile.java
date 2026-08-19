package cn.kong.eon.agent.profile;

/**
 * 请求 Profile。
 *   SIMPLE — 默认模式，两阶段懒加载工具 Schema。
 *   TASK   — todo_write 后升级，TodoNavigator 激活，工具挂载逻辑与 SIMPLE 一致。
 */
public enum RequestProfile {
    SIMPLE,
    TASK
}
