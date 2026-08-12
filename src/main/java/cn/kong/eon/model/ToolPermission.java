package cn.kong.eon.model;

/**
 * 工具权限分级。
 * 对应技术方案第 5.1 节。
 */
public enum ToolPermission {
    READONLY,       // 只读：todo_read, web_search, web_read
    RESTRICTED_WRITE,  // 受限写：todo_write, working_memory
    DESTRUCTIVE     // 破坏性：download（需门禁审批）
}
