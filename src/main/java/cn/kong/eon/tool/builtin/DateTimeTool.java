package cn.kong.eon.tool.builtin;

import cn.kong.eon.model.SessionState;
import cn.kong.eon.model.ToolPermission;
import cn.kong.eon.tool.ToolContext;
import cn.kong.eon.tool.ToolDescriptor;
import cn.kong.eon.tool.ToolExecutor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * date_time 工具：获取当前系统日期和时间。
 * 当用户提问与时间相关（如"今天""现在""这周""几号"等）时，应优先调用此工具获取准确时间。
 */
public class DateTimeTool implements ToolExecutor {

    public static ToolDescriptor descriptor() {
        Map<String, Map<String, Object>> props = new LinkedHashMap<>();
        props.put("timezone", Map.of(
                "type", "string",
                "description", "可选。时区偏移量，如 \"+08:00\"。不传则使用系统默认时区。"
        ));
        String desc = "获取当前系统日期和时间。当用户提问涉及时间（如\"今天\"\"现在\"\"这周\"\"几号\"\"星期几\"等）时，"
                + "应优先调用此工具获取准确时间，不要自行推测。";
        return new ToolDescriptor(
                "date_time",
                desc,
                ToolPermission.READONLY,
                ToolDescriptor.buildSpec("date_time", desc, props),
                new DateTimeTool()
        );
    }

    @Override
    public String execute(Map<String, Object> arguments, SessionState state, ToolContext context) {
        LocalDateTime now = LocalDateTime.now();
        LocalDate today = now.toLocalDate();

        DateTimeFormatter fullFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.CHINA);
        DateTimeFormatter dateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.CHINA);
        DateTimeFormatter timeFmt = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.CHINA);

        // 星期几（中文）
        String weekday = today.getDayOfWeek().getDisplayName(
                java.time.format.TextStyle.FULL, Locale.CHINA);

        // ISO 周数
        int weekOfYear = today.get(java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR);
        int weekBasedYear = today.get(java.time.temporal.IsoFields.WEEK_BASED_YEAR);

        // 今年第几天
        int dayOfYear = today.getDayOfYear();

        // 年初/年末
        LocalDate yearStart = LocalDate.of(today.getYear(), 1, 1);
        LocalDate yearEnd = LocalDate.of(today.getYear(), 12, 31);

        StringBuilder sb = new StringBuilder();
        sb.append("当前系统时间：\n");
        sb.append("  完整时间: ").append(now.format(fullFmt)).append("\n");
        sb.append("  日期: ").append(today.format(dateFmt)).append("\n");
        sb.append("  时间: ").append(now.format(timeFmt)).append("\n");
        sb.append("  星期: ").append(weekday).append("\n");
        sb.append("  ISO周: ").append(weekBasedYear).append(" 年第 ").append(weekOfYear).append(" 周\n");
        sb.append("  今年第 ").append(dayOfYear).append(" 天\n");
        sb.append("  今年剩余: ").append(yearEnd.getDayOfYear() - dayOfYear).append(" 天\n");

        return sb.toString();
    }
}
