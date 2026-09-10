package cn.kong.eon.event;

/**
 * AgentMessage 的内容部件。常见类型为 text。
 */
public record ContentPart(String type, String text) {

    /** 创建文本部件。 */
    public static ContentPart text(String text) {
        return new ContentPart("text", text);
    }
}
