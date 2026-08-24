package cn.kong.eon.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/** Artifact 引用记录。大文本工具结果落盘后，上下文只保留此引用。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ArtifactRef {
    private String refId;
    private String source;     // 来源工具名
    private String summary;
    private long sizeChars;    // 原始字符数
    private String filePath;   // 落盘文件路径
    private Instant createdAt;

    public ArtifactRef() {}

    public static ArtifactRef of(String refId, String source, String summary, long sizeChars, String filePath) {
        ArtifactRef a = new ArtifactRef();
        a.refId = refId;
        a.source = source;
        a.summary = summary;
        a.sizeChars = sizeChars;
        a.filePath = filePath;
        a.createdAt = Instant.now();
        return a;
    }

    public String getRefId() { return refId; }
    public void setRefId(String refId) { this.refId = refId; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }

    public long getSizeChars() { return sizeChars; }
    public void setSizeChars(long sizeChars) { this.sizeChars = sizeChars; }

    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
