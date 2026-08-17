package cn.kong.eon.agent.profile;

/**
 * 请求 Profile（由 PolicyRouter 分配）。
 *
 * <p>简化为两档设计：
 * <ul>
 *   <li>{@code SIMPLE}：默认模式，始终注入 tool_catalog（名称+摘要），
 *       不挂载完整工具 Schema。模型从 catalog 中选择需要的工具后，
 *       下一轮按声明的工具名挂载完整 Schema（两阶段懒加载）。</li>
 *   <li>{@code TASK}：LLM 调用过 todo_write 后自动升级，全量挂载工具 Schema，
 *       TodoNavigator 激活。Profile 升级单向，不可降级。</li>
 * </ul>
 */
public enum RequestProfile {
    SIMPLE,
    TASK
}
