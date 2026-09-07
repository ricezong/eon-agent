package cn.kong.eon.agent.context;

import cn.kong.eon.model.ArtifactRef;

/**
 * 大内容落盘能力。context 包对 store 包的依赖倒置接口，
 * 由 {@code ArtifactStore} 实现并在装配期注入。
 */
public interface StoreSupport {

    /**
     * 保存完整内容，返回引用。refId 由 messageSeq 确定性派生——
     * 同一消息重复入站（会话恢复的回放）落到同一文件，幂等。
     */
    ArtifactRef save(String source, String content, int messageSeq);
}
