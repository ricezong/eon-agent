package cn.kong.eon.agent.context;

/**
 * 头尾保留截断。保留内容的前半和后半，中段以省略号替代，不带任何文字说明。
 */
public final class TextTrimmer {

    private TextTrimmer() {
    }

    /**
     * @param keepChars 保留的总字符数，头尾各半
     * @return 截断后的文本；原文未超过保留长度时原样返回
     */
    public static String headTail(String content, int keepChars) {
        if (content == null) return "";
        int headChars = keepChars / 2;
        int tailChars = keepChars - headChars;
        if (content.length() <= headChars + tailChars) return content;
        return content.substring(0, headChars)
                + "\n...\n"
                + content.substring(content.length() - tailChars);
    }
}
