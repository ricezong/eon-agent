package cn.kong.eon.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Insights 滚动存储。
 * 对应技术方案第 4 节「Insights 滚动区」。
 * 模型通过 working_memory 工具写入关键发现。
 * 上限 40 条 / 8000 字符，超出淘汰最旧的。
 */
public class InsightsStore {
    private static final Logger log = LoggerFactory.getLogger(InsightsStore.class);

    private final int maxItems;
    private final int maxChars;
    private final List<String> insights = new CopyOnWriteArrayList<>();

    public InsightsStore(int maxItems, int maxChars) {
        this.maxItems = maxItems;
        this.maxChars = maxChars;
    }

    /**
     * 追加一条 insight（最新在前）。
     */
    public synchronized void add(String insight) {
        insights.add(0, insight);
        evict();
        log.debug("Insight added: {} (total={})", insight.substring(0, Math.min(50, insight.length())), insights.size());
    }

    public List<String> getAll() {
        return new ArrayList<>(insights);
    }

    public void clear() {
        insights.clear();
    }

    public int size() {
        return insights.size();
    }

    /**
     * 淘汰超限的旧条目。
     */
    private void evict() {
        while (insights.size() > maxItems) {
            insights.remove(insights.size() - 1);
        }
        int totalChars = insights.stream().mapToInt(String::length).sum();
        while (totalChars > maxChars && insights.size() > 1) {
            String removed = insights.remove(insights.size() - 1);
            totalChars -= removed.length();
        }
    }
}
