package cn.kong.eon.agent.context.policy;

import cn.kong.eon.agent.context.TextTrimmer;
import cn.kong.eon.agent.context.block.BlockKind;
import cn.kong.eon.agent.context.block.ContextBlock;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 占位文本生成器。处置过程中所有替换文本的唯一出处，
 * 使"块被换成什么"这件事只有一处定义，不散落在各条处置逻辑里。
 * <p>
 * 三个方法分别是三类替换：头尾截断、清空、参数骨架化。
 * 返回 null 一律表示"这个块不适合这样替换"，调用方据此跳过。
 */
public final class Placeholders {

    /** 参数中超过该长度的字符串字段才被视为大字段并替换。 */
    private static final int LONG_FIELD_CHARS = 200;

    private static final List<String> PATH_KEYS =
            List.of("file_path", "path", "output_path", "file", "filepath");

    private Placeholders() {
    }

    /**
     * 头尾保留截断：保留开头与结尾，中段以省略号替代。
     *
     * @param keepChars 保留的总字符数，头尾各半
     * @return 截断后的文本；原文未超过保留长度时返回 null
     */
    public static String headTail(ContextBlock block, int keepChars) {
        String raw = block.text();
        if (raw.length() <= keepChars) return null;
        return TextTrimmer.headTail(raw, keepChars);
    }

    /**
     * 清空：把整块内容替换为一行占位文本。
     * 磁盘上有副本时占位文本带上引用，模型可据此取回原文。
     * <p>
     * 只应对 recoverable 的块调用——无副本的块是唯一一份，清空后无法恢复。
     */
    public static String cleared(ContextBlock block) {
        String label = labelOf(block);
        return block.refId() != null
                ? "[旧" + label + "内容已清除。完整内容已保存至 artifact://" + block.refId()
                + "，可用 read_file 工具读取]"
                : "[旧" + label + "内容已清除]";
    }

    /**
     * 参数骨架化：把参数 JSON 中的长字符串字段替换为一行说明，保留其余字段与结构。
     * 输出必须是严格合法的 JSON——这段文本会作为历史工具调用的 arguments 原样回传给模型，
     * 供应商会校验该字段格式，不合法会直接拒收整个请求。
     *
     * @return 骨架 JSON；参数无法解析、不含长字段或序列化失败时返回 null，
     *         调用方必须据此放弃替换——宁可多占 token，也不能发出会被拒收的请求
     */
    public static String skeleton(ContextBlock block, ObjectMapper mapper) {
        Map<String, Object> args = parse(block.text(), mapper);
        if (args.isEmpty()) return null;

        String path = extractPath(args);
        String note = path != null
                ? "内容已完整落盘至 " + path + "，可用 read_file 读取"
                : "内容已落盘，可用 read_file 读取";

        Map<String, Object> slim = new LinkedHashMap<>();
        boolean replacedAny = false;
        for (Map.Entry<String, Object> entry : args.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String s && s.length() > LONG_FIELD_CHARS) {
                slim.put(entry.getKey(), "<" + s.length() + " 字符已清空：" + note + ">");
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

    private static String labelOf(ContextBlock block) {
        return block.kind() == BlockKind.TOOL_ARGS ? "工具参数" : "工具结果";
    }

    /** 从参数中提取落盘路径，参数非法或不含路径字段时返回 null。 */
    private static String extractPath(Map<String, Object> args) {
        for (String key : PATH_KEYS) {
            Object v = args.get(key);
            if (v instanceof String s && !s.isBlank()) return s;
        }
        return null;
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
