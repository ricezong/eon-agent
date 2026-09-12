package cn.kong.eon.context.dynamic;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 动态环境上下文。每次构建上下文时实时生成
 */
public class EnvironmentContext {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy年M月d日 EEEE");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("H:mm");

    /** 额外的环境条目。 */
    private final List<String> extras = new ArrayList<>();

    public void addExtra(String extra) {
        if (extra != null && !extra.isBlank()) {
            extras.add(extra);
        }
    }

    /** 渲染为注入文本，无内容时返回空串。 */
    public String render() {
        StringBuilder sb = new StringBuilder();
        LocalDateTime now = LocalDateTime.now();

        sb.append("当前时间: ").append(now.format(DATE_FMT))
          .append(" ").append(now.format(TIME_FMT));


        for (String extra : extras) {
            sb.append("\n").append(extra);
        }

        return sb.toString();
    }

}
