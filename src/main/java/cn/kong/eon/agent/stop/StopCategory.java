package cn.kong.eon.agent.stop;

import java.util.regex.Pattern;

/**
 * 终止类别枚举。每个类别自带模板文案，{@link #format} 填充占位符后得到完整终止描述。
 */
public enum StopCategory {

    BUDGET_EXCEEDED("预算超限: 已用 {0} / {1} tokens"),
    LOOP_DETECTED("检测到死循环: {0}"),
    GATE_REJECTED("门禁拒绝: 破坏性工具 {0} 需要用户审批"),
    FAILURE_BREAKER("失败熔断: {0}"),
    MAX_STEPS_REACHED("达到最大步数: {0}"),
    UNEXPECTED_ERROR("执行异常: {0}");

    /** 匹配 {N} 占位符。 */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\d+}");

    private final String template;

    StopCategory(String template) {
        this.template = template;
    }

    /**
     * 用参数填充模板。无参时去掉末尾 ": {N}" 后缀。
     */
    public String format(Object... args) {
        if (args == null || args.length == 0) {
            return template.replaceAll(": \\{\\d+}$", "");
        }
        String pattern = PLACEHOLDER.matcher(template).replaceAll("%s");
        return String.format(pattern, args);
    }
}
