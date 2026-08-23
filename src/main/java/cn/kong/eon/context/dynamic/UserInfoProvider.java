package cn.kong.eon.context.dynamic;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 生成 <user_info> 动态注入块。
 * 内容：操作系统、当前日期时间、时区、用户语言、工作目录。
 * 仅包含个人助手每次推理都需要的通用信息。
 */
public class UserInfoProvider {

    /**
     * 生成 user_info 注入文本。
     * @param workDir 工作目录绝对路径
     * @return <user_info> 块文本
     */
    public static String generate(String workDir) {
        String os = System.getProperty("os.name", "unknown");
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(zone);
        LocalTime now = LocalTime.now(zone);
        String timezone = zone.getId();

        StringBuilder sb = new StringBuilder("<user_info>\n");
        sb.append("- 操作系统: ").append(os).append("\n");
        sb.append("- 当前日期: ").append(today.format(DateTimeFormatter.ISO_DATE)).append("\n");
        sb.append("- 当前时间: ").append(now.format(DateTimeFormatter.ofPattern("HH:mm"))).append("\n");
        sb.append("- 时区: ").append(timezone).append("\n");
        sb.append("- 语言: 中文\n");
        sb.append("- 工作目录: ").append(workDir != null ? workDir : "(未设置)").append("\n");
        sb.append("</user_info>");
        return sb.toString();
    }
}
