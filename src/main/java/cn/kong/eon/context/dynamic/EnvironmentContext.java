package cn.kong.eon.context.dynamic;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 动态环境上下文。每次构建上下文时实时生成，注入当前时间、工作目录等信息。
 */
public class EnvironmentContext {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy年M月d日 EEEE");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("H:mm");

    /** 会话级子目录名称。 */
    private static final List<String> SESSION_DIRS = List.of(
            "scripts", "download", "upload", "skills", "tool-results");

    private final List<String> extras = new ArrayList<>();

    public void addExtra(String extra) {
        if (extra != null && !extra.isBlank()) {
            extras.add(extra);
        }
    }

    /** 渲染为注入文本。 */
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

    /**
     * 快捷工厂：根据会话根目录构建，自动注入会话下所有子目录路径。
     */
    public static EnvironmentContext of(String sessionDir) {
        EnvironmentContext ctx = new EnvironmentContext();
        if (sessionDir != null && !sessionDir.isBlank()) {
            Path root = Path.of(sessionDir).toAbsolutePath().normalize();
            for (String sub : SESSION_DIRS) {
                Path dir = root.resolve(sub);
                if (Files.isDirectory(dir)) {
                    ctx.addExtra(switch (sub) {
                        case "scripts" -> "脚本目录: " + dir;
                        case "download" -> "工作目录: " + dir;
                        case "upload" -> "上传目录: " + dir;
                        case "skills" -> "技能目录: " + dir;
                        case "tool-results" -> "工具结果目录: " + dir;
                        default -> sub + "目录: " + dir;
                    });
                }
            }
        }
        return ctx;
    }
}
