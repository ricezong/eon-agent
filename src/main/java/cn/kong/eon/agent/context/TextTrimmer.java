package cn.kong.eon.agent.context;

/**
 * 头尾保留截断。保留内容的前半和后半，中间用省略标记替代，
 * 让模型既知道开头（结构、意图）也知道结尾（结论、错误）。
 */
public final class TextTrimmer {

    private TextTrimmer() {
    }

    /**
     * @param keepChars 保留的总字符数，头尾各半
     */
    public static String headTail(String content, int keepChars) {
        if (content == null) return "";
        int headChars = keepChars / 2;
        int tailChars = keepChars - headChars;
        if (content.length() <= headChars + tailChars) return content;
        return content.substring(0, headChars)
                + "\n... [中间内容已省略] ...\n"
                + content.substring(content.length() - tailChars);
    }
}
