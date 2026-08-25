package cn.kong.eon.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * 跨会话记忆存储。
 * 记忆文件存储在 {storage.base_dir}/memories/ 下，跨会话共享。
 * P3 阶段将完善 update_memory 工具集成；当前为最小可用实现。
 */
public class MemoryStore {
    private static final Logger log = LoggerFactory.getLogger(MemoryStore.class);

    private final Path memoryDir;
    private final ObjectMapper mapper;

    public MemoryStore(Path baseDir, ObjectMapper objectMapper) {
        this.memoryDir = baseDir.resolve("memories");
        this.mapper = objectMapper.copy().enable(SerializationFeature.INDENT_OUTPUT);
        try {
            Files.createDirectories(memoryDir);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create memory dir", e);
        }
    }

    /** 记忆条目。 */
    public static class MemoryItem {
        public String id;
        public String title;
        public String content;
        public Instant createdAt;
        public Instant updatedAt;

        public MemoryItem() {}

        public MemoryItem(String id, String title, String content) {
            this.id = id;
            this.title = title;
            this.content = content;
            this.createdAt = Instant.now();
            this.updatedAt = this.createdAt;
        }
    }

    /** 创建新记忆。 */
    public MemoryItem create(String title, String content) {
        String id = "mem_" + UUID.randomUUID().toString().substring(0, 8);
        MemoryItem item = new MemoryItem(id, title, content);
        save(item);
        log.info("Memory created: {} - {}", id, title);
        return item;
    }

    /** 更新已有记忆。 */
    public MemoryItem update(String id, String title, String content) {
        MemoryItem existing = load(id);
        if (existing == null) {
            throw new IllegalArgumentException("Memory not found: " + id);
        }
        if (title != null) existing.title = title;
        if (content != null) existing.content = content;
        existing.updatedAt = Instant.now();
        save(existing);
        log.info("Memory updated: {}", id);
        return existing;
    }

    /** 删除记忆。 */
    public boolean delete(String id) {
        Path file = memoryDir.resolve(id + ".json");
        try {
            boolean deleted = Files.deleteIfExists(file);
            if (deleted) log.info("Memory deleted: {}", id);
            return deleted;
        } catch (IOException e) {
            log.error("Failed to delete memory: {}", id, e);
            return false;
        }
    }

    /** 加载全部记忆（按创建时间排序，保证注入顺序确定性）。 */
    public List<MemoryItem> loadAll() {
        List<MemoryItem> items = new ArrayList<>();
        try (var stream = Files.list(memoryDir)) {
            stream.filter(p -> p.getFileName().toString().startsWith("mem_"))
                  .filter(p -> p.toString().endsWith(".json"))
                  .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                  .forEach(p -> {
                      try {
                          items.add(mapper.readValue(p.toFile(), MemoryItem.class));
                      } catch (IOException e) {
                          log.warn("Failed to read memory file: {}", p, e);
                      }
                  });
        } catch (IOException e) {
            log.warn("Failed to list memories: {}", e.getMessage());
        }
        items.sort(Comparator.comparing(m -> m.createdAt));
        return items;
    }

    /** 渲染为注入块文本。 */
    public String renderForInjection() {
        List<MemoryItem> items = loadAll();
        if (items.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("<memories>\n");
        for (MemoryItem m : items) {
            sb.append("- [").append(m.id).append("] ").append(m.title)
              .append(": ").append(truncate(m.content, 200)).append("\n");
        }
        sb.append("</memories>");
        return sb.toString();
    }

    /**
     * 将文本中的 [[memory:xxx]] 引用替换为 "标题（内容摘要）"。
     * 用于最终输出渲染。
     */
    public String renderReferences(String text) {
        if (text == null || text.isEmpty()) return text;
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("\\[\\[memory:(mem_[a-f0-9]+)\\]\\]");
        java.util.regex.Matcher m = p.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String memId = m.group(1);
            MemoryItem item = load(memId);
            if (item != null) {
                String replacement = item.title + "（" + truncate(item.content, 60) + "）";
                m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(replacement));
            } else {
                // 记忆不存在，保留原文
                m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(m.group()));
            }
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private MemoryItem load(String id) {
        Path file = memoryDir.resolve(id + ".json");
        if (!Files.exists(file)) return null;
        try {
            return mapper.readValue(file.toFile(), MemoryItem.class);
        } catch (IOException e) {
            log.error("Failed to read memory: {}", id, e);
            return null;
        }
    }

    private void save(MemoryItem item) {
        Path file = memoryDir.resolve(item.id + ".json");
        try {
            mapper.writeValue(file.toFile(), item);
        } catch (IOException e) {
            log.error("Failed to save memory: {}", item.id, e);
        }
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
