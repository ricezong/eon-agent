package cn.kong.eon.model;

/** 工具权限分级：READONLY → RESTRICTED_WRITE → DESTRUCTIVE。 */
public enum ToolPermission {
    READONLY,
    RESTRICTED_WRITE,
    DESTRUCTIVE
}
