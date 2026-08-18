package cn.kong.eon.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;

/**
 * Todo 任务项。核心字段：id / content / status / priority。
 * 扩展：depends_on / artifacts / notes / block_reason。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TodoItem {
    private String id;
    private String content;
    private TodoStatus status;
    private String priority;            // high / medium / low
    private String parentId;
    private List<String> dependsOn;
    private List<String> artifacts;     // 关联的 artifact refId
    private String notes;
    private String blockReason;         // blocked 时必填
    private int version;                // 乐观锁
    private int lastModifiedTurn;

    public TodoItem() {
        this.dependsOn = new ArrayList<>();
        this.artifacts = new ArrayList<>();
        this.version = 1;
    }

    public static TodoItem of(String id, String content, String priority) {
        TodoItem t = new TodoItem();
        t.id = id;
        t.content = content;
        t.status = TodoStatus.PENDING;
        t.priority = priority != null ? priority : "medium";
        return t;
    }

    // --- getters / setters ---

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public TodoStatus getStatus() { return status; }
    public void setStatus(TodoStatus status) { this.status = status; }

    public String getPriority() { return priority; }
    public void setPriority(String priority) { this.priority = priority; }

    public String getParentId() { return parentId; }
    public void setParentId(String parentId) { this.parentId = parentId; }

    public List<String> getDependsOn() { return dependsOn; }
    public void setDependsOn(List<String> dependsOn) { this.dependsOn = dependsOn != null ? dependsOn : new ArrayList<>(); }

    public List<String> getArtifacts() { return artifacts; }
    public void setArtifacts(List<String> artifacts) { this.artifacts = artifacts != null ? artifacts : new ArrayList<>(); }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }

    public String getBlockReason() { return blockReason; }
    public void setBlockReason(String blockReason) { this.blockReason = blockReason; }

    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }

    public int getLastModifiedTurn() { return lastModifiedTurn; }
    public void setLastModifiedTurn(int lastModifiedTurn) { this.lastModifiedTurn = lastModifiedTurn; }

    @Override
    public String toString() {
        return status.icon() + " #" + id + " " + content + "（" + status.label() + "）";
    }
}
