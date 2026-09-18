package cn.kong.eon.store.artifact;

/**
 * Artifact 引用。大文本工具结果落盘后，上下文只保留此引用。
 * refId 与文件路径由消息序号确定性派生，同名同内容的重复落盘为幂等覆盖。
 */
public record ArtifactRef(String refId, String filePath) {
}
