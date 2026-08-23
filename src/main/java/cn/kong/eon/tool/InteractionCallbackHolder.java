package cn.kong.eon.tool;

/**
 * 交互回调 holder。延迟设置 callback 实例。
 * <p>
 * 用于解决 AgentBootstrapFactory 中的循环依赖：
 * ToolContext 需要在 EonAgent 构造前创建，
 * 但 InteractionCallback 需要 AgentSession 实例才能创建。
 * <p>
 * 通过 holder 延迟绑定：先创建 holder（callback=null），
 * AgentSession 创建后再设置实际的 callback。
 */
public class InteractionCallbackHolder implements InteractionCallback {

    private volatile InteractionCallback delegate;

    public void setDelegate(InteractionCallback delegate) {
        this.delegate = delegate;
    }

    @Override
    public java.util.Map<String, String> askQuestions(
            java.util.List<java.util.Map<String, Object>> questions, String title) {
        if (delegate == null) {
            throw new IllegalStateException("InteractionCallback 尚未设置");
        }
        return delegate.askQuestions(questions, title);
    }
}
