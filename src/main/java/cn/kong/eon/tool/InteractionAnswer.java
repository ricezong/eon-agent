package cn.kong.eon.tool;

import java.util.List;

/**
 * 用户对一次提问的回答。每项按提问时的 question id 对应；
 * labels 是选中的选项文案，other 是「其他」的自填内容，两者都可能存在。
 */
public record InteractionAnswer(
        List<AnswerItem> answers
) {

    public record AnswerItem(
            String id,
            List<String> labels,
            String other
    ) {
    }
}
