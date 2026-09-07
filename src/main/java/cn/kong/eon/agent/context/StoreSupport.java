package cn.kong.eon.agent.context;

import cn.kong.eon.model.ArtifactRef;

/**
 * 大内容落盘能力。由 ArtifactStore 实现并注入。
 */
public interface StoreSupport {

    /**
     * 保存完整内容，返回引用。refId 由 messageSeq 派生，同一消息重复入室幂等。
     */
    ArtifactRef save(String source, String content, int messageSeq);
}
