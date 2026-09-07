package cn.kong.eon.model;

/**
 * Artifact 引用。大文本工具结果落盘后，上下文只保留此引用。
 * <p>
 * refId 与文件路径由消息序号确定性派生（见 {@code ArtifactStore}）：
 * 同一条消息无论入站多少次（常规写入或会话恢复的回放），都指向同一个文件，
 * 重复落盘退化为同名同内容的幂等覆盖。
 */
public record ArtifactRef(String refId, String filePath) {
}
