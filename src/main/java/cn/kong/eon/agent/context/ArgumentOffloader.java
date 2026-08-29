package cn.kong.eon.agent.context;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具参数的无损卸载：把"参数全量"替换为"参数骨架 + 路径引用"。
 * <p>
 * 入站的 {@code ArgumentOffloadRule} 与在站的 {@code BudgetAwareOffloadRule}
 * 是同一件事的两个入口，共用本类的实现——两份实现已经一起写错过一次
 * （都在 JSON 外追加注释，导致请求被供应商拒收），合并是唯一的修法。
 * <p>
 * <b>为什么无损</b>：声明了 {@code persistsArguments()} 的工具（典型是 write）
 * 已把内容完整写进磁盘，历史里那份 arguments 与磁盘文件逐字节重复，
 * 替换成路径引用不损失任何信息，模型需要时可 read_file 取回。
 * <p>
 * <b>硬约束：输出必须是严格合法的 JSON。</b>
 * 这段文本会作为历史工具调用的 {@code arguments} 原样回传给模型，
 * 供应商会校验该字段格式，不合法会直接拒收整个请求
 * （{@code 400 Invalid request parameters}）。
 * 因此说明文字只能放在 JSON 的字符串值<i>内部</i>，
 * JSON 外壳之外不允许有任何内容（注释也不行）。
 */
public final class ArgumentOffloader {

    /** 超过该长度的字符串字段才被视为"大字段"并替换。 */
    private static final int LONG_FIELD_CHARS = 200;

    private static final List<String> PATH_KEYS =
            List.of("file_path", "path", "output_path", "file", "filepath");

    private ArgumentOffloader() {
    }

    /**
     * 把参数 JSON 替换为"骨架 + 路径引用"。
     *
     * @param argumentsJson 原始参数 JSON
     * @param path          落盘路径，用于生成说明；可为 null
     * @param mapper        序列化器
     * @return 严格合法的 JSON 字符串；
     *         无可替换的大字段、解析失败或序列化失败时返回 <b>null</b>，
     *         调用方必须据此<b>放弃卸载</b>——宁可多占几轮 token，
     *         也不能发出一个会被拒收的请求
     */
    public static String offload(String argumentsJson, String path, ObjectMapper mapper) {
        Map<String, Object> args = parse(argumentsJson, mapper);
        if (args.isEmpty()) return null;

        Map<String, Object> slim = new LinkedHashMap<>();
        boolean replacedAny = false;
        for (Map.Entry<String, Object> entry : args.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String s && s.length() > LONG_FIELD_CHARS) {
                slim.put(entry.getKey(), "<" + s.length() + " 字符已卸载：" + note(path) + ">");
                replacedAny = true;
            } else {
                slim.put(entry.getKey(), value);
            }
        }
        if (!replacedAny) return null;

        try {
            return mapper.writeValueAsString(slim);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 从参数中提取落盘路径，用于生成"内容已落盘至 X"的说明。
     *
     * @return 路径；参数非法或不含路径字段时返回 null
     */
    public static String extractPath(String argumentsJson, ObjectMapper mapper) {
        Map<String, Object> args = parse(argumentsJson, mapper);
        for (String key : PATH_KEYS) {
            Object v = args.get(key);
            if (v instanceof String s && !s.isBlank()) return s;
        }
        return null;
    }

    /** 卸载说明文本。只能作为 JSON 的字符串值使用，不能拼在 JSON 外面。 */
    public static String note(String path) {
        return path != null
                ? "内容已完整落盘至 " + path + "，可用 read_file 读取"
                : "内容已落盘，可用 read_file 读取";
    }

    private static Map<String, Object> parse(String argumentsJson, ObjectMapper mapper) {
        if (argumentsJson == null || argumentsJson.isBlank() || mapper == null) return Map.of();
        try {
            return mapper.readValue(argumentsJson, new TypeReference<>() {
            });
        } catch (Exception e) {
            return Map.of();
        }
    }
}
