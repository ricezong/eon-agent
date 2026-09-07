package cn.kong.eon.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 会话注册表：负责会话的**发现与定位**，是加载链路的入口。
 * <p>
 * 数据源全部是磁盘上已有的产物，本类不参与写入链路（软删除标记除外）：
 * <ul>
 *   <li>最后活跃时间 = 账本 {@code transcript.jsonl} 的修改时间——append 必然刷新它，
 *       等价于"最后一条消息写入的时刻"，据此按时间倒序回答"哪个是上一次"；</li>
 *   <li>标题 = 账本前若干行里第一条用户消息，本地推导，不额外调用模型；</li>
 *   <li>消息数 / 有无快照 / 摘要预览，供列表展示与恢复决策参考。</li>
 * </ul>
 * 会话量级在十几到几十，直接全目录扫描即可，不引入索引文件或数据库。
 */
public class SessionRegistry {

    private static final Logger log = LoggerFactory.getLogger(SessionRegistry.class);

    public static final String SESSION_ID_PREFIX = "eon_";
    /** 账本文件名，用于取最后活跃时间与统计消息数。 */
    private static final String TRANSCRIPT = "transcript.jsonl";
    /** 会话快照文件名，有无快照决定能否 RESUME。 */
    private static final String SNAPSHOT = "session.json";
    /** 软删除名单，记录已删除但仍留在磁盘上的会话 id。 */
    private static final String DELETED_FILE = "deleted.json";

    private static final int TITLE_SCAN_LINES = 10;
    private static final int TITLE_MAX_CHARS = 40;
    private static final int PREVIEW_MAX_CHARS = 50;

    /**
     * 会话列表项。{@code title} 与 {@code lastActivityAt} 恒非空——
     * 前者取不到时回退为会话 id，后者回退为目录修改时间，保证列表可排序、可展示。
     */
    public record SessionSummary(
            String sessionId,
            String title,
            Instant lastActivityAt,
            long messageCount,
            boolean hasSnapshot,
            String summaryPreview
    ) {}

    private final Path baseDir;
    private final ObjectMapper mapper;

    public SessionRegistry(Path baseDir, ObjectMapper mapper) {
        this.baseDir = baseDir;
        this.mapper = mapper;
    }

    /**
     * 列出未删除的会话，按最后活跃时间倒序——首个元素即"上一次"。
     */
    public List<SessionSummary> list() {
        Set<String> deleted = readDeleted();
        try (Stream<Path> stream = Files.list(baseDir)) {
            return stream.filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .filter(name -> name.startsWith(SESSION_ID_PREFIX))
                    .filter(name -> !deleted.contains(name))
                    .map(this::summarize)
                    .filter(Objects::nonNull)
                    .sorted(Comparator.comparing(SessionSummary::lastActivityAt).reversed())
                    .toList();
        } catch (IOException e) {
            log.error("列举会话失败: {}", baseDir, e);
            return List.of();
        }
    }

    /**
     * 最近活跃的会话。没有历史会话时返回 empty——调用方据此决定是报错还是新起会话。
     */
    public Optional<SessionSummary> last() {
        List<SessionSummary> all = list();
        return all.isEmpty() ? Optional.empty() : Optional.of(all.get(0));
    }

    /**
     * 按完整 id 定位，或按唯一前缀定位。
     *
     * @return 命中且唯一时返回该会话；未命中或前缀歧义时返回 empty
     */
    public Optional<SessionSummary> find(String idOrPrefix) {
        if (idOrPrefix == null || idOrPrefix.isBlank()) return Optional.empty();
        List<SessionSummary> all = list();

        Optional<SessionSummary> exact = all.stream()
                .filter(s -> s.sessionId().equals(idOrPrefix))
                .findFirst();
        if (exact.isPresent()) return exact;

        List<SessionSummary> byPrefix = all.stream()
                .filter(s -> s.sessionId().startsWith(idOrPrefix))
                .toList();
        return byPrefix.size() == 1 ? Optional.of(byPrefix.get(0)) : Optional.empty();
    }

    /**
     * 软删除：把会话 id 记入 {@code deleted.json}，列表与 {@link #last()} 均会过滤它。
     * 账本与快照仍留在磁盘上，手工删掉该条记录即可恢复。
     */
    public void delete(String sessionId) {
        Set<String> ids = readDeleted();
        if (!ids.add(sessionId)) return;
        Path file = baseDir.resolve(DELETED_FILE);
        try {
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            mapper.writeValue(tmp.toFile(), ids);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException nonAtomic) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            log.info("会话已删除: {}", sessionId);
        } catch (IOException e) {
            log.error("记录会话删除失败: {}", sessionId, e);
        }
    }

    private SessionSummary summarize(String sessionId) {
        Path dir = baseDir.resolve(sessionId);
        Path transcript = dir.resolve(TRANSCRIPT);
        Path snapshot = dir.resolve(SNAPSHOT);

        String title = deriveTitle(transcript);
        return new SessionSummary(
                sessionId,
                title != null ? title : sessionId,
                lastActivityAt(dir, transcript),
                countLines(transcript),
                Files.exists(snapshot),
                readSummaryPreview(snapshot)
        );
    }

    /**
     * 账本前若干行里的第一条用户消息。
     * 早期版本的账本由写入方把 {@code <user_query>} 拼进内容，此处剥掉历史数据里的这层标签；
     * 现在的账本只记原文（标签在渲染时添加），走不到剥离分支。
     */
    private String deriveTitle(Path transcript) {
        if (!Files.exists(transcript)) return null;
        try (Stream<String> lines = Files.lines(transcript, StandardCharsets.UTF_8)) {
            return lines.limit(TITLE_SCAN_LINES)
                    .map(this::parseUserText)
                    .filter(Objects::nonNull)
                    .findFirst()
                    .map(SessionRegistry::shorten)
                    .orElse(null);
        } catch (IOException e) {
            log.warn("读取账本首条用户消息失败: {}", transcript, e);
            return null;
        }
    }

    private String parseUserText(String line) {
        try {
            var node = mapper.readTree(line);
            if (!"user".equals(node.path("type").asText())) return null;
            String content = node.path("content").asText(null);
            if (content == null || content.isBlank()) return null;
            return content.replace("<user_query>", "")
                    .replace("</user_query>", "")
                    .trim();
        } catch (Exception e) {
            return null;
        }
    }

    private static String shorten(String text) {
        String flat = text.replace("\n", " ").replace("\r", " ").trim();
        return flat.length() <= TITLE_MAX_CHARS ? flat : flat.substring(0, TITLE_MAX_CHARS) + "…";
    }

    private Instant lastActivityAt(Path dir, Path transcript) {
        Path probe = Files.exists(transcript) ? transcript : dir;
        try {
            return Files.getLastModifiedTime(probe).toInstant();
        } catch (IOException e) {
            log.warn("读取会话最后活跃时间失败: {}", probe, e);
            return Instant.EPOCH;
        }
    }

    private long countLines(Path transcript) {
        if (!Files.exists(transcript)) return 0;
        try (Stream<String> lines = Files.lines(transcript, StandardCharsets.UTF_8)) {
            return lines.count();
        } catch (IOException e) {
            log.warn("统计账本行数失败: {}", transcript, e);
            return 0;
        }
    }

    private String readSummaryPreview(Path snapshot) {
        if (!Files.exists(snapshot)) return null;
        try {
            var node = mapper.readTree(snapshot.toFile())
                    .path("compressionState").path("lastSummary");
            if (node.isMissingNode() || node.isNull()) return null;
            String text = node.asText().replace("\n", " ").trim();
            return text.isEmpty() ? null : text.substring(0, Math.min(PREVIEW_MAX_CHARS, text.length()));
        } catch (Exception e) {
            log.warn("读取会话摘要预览失败: {}", snapshot, e);
            return null;
        }
    }

    private Set<String> readDeleted() {
        Path file = baseDir.resolve(DELETED_FILE);
        if (!Files.exists(file)) return new LinkedHashSet<>();
        try {
            return new LinkedHashSet<>(mapper.readValue(file.toFile(), new TypeReference<List<String>>() {
            }));
        } catch (Exception e) {
            log.warn("读取已删除会话名单失败: {}", file, e);
            return new LinkedHashSet<>();
        }
    }
}
