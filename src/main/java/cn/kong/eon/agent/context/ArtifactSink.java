package cn.kong.eon.agent.context;

import cn.kong.eon.model.ArtifactRef;

/**
 * 大内容落盘能力。context 包对 store 包的依赖倒置接口，
 * 由 {@code ArtifactStore} 实现并在装配期注入。
 */
public interface ArtifactSink {

    /** 无落盘能力时的空实现 */
    ArtifactSink NONE = (source, content, summary) -> null;

    /**
     * 保存完整内容，返回引用。
     */
    ArtifactRef save(String source, String content, String summary);
}
