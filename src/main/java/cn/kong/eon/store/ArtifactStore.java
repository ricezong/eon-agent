package cn.kong.eon.store;

import cn.kong.eon.model.ArtifactRef;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Artifact 存储。大文本工具结果落盘，上下文只保留引用。
 */
public class ArtifactStore {
    private static final Logger log = LoggerFactory.getLogger(ArtifactStore.class);

    private final Path artifactDir;
    private final ObjectMapper mapper;
    private final Map<String, ArtifactRef> refs = new ConcurrentHashMap<>();
    private final AtomicInteger counter = new AtomicInteger(0);

    public ArtifactStore(Path artifactDir) {
        this.artifactDir = artifactDir;
        this.mapper = new ObjectMapper();
        this.mapper.enable(SerializationFeature.INDENT_OUTPUT);
        try {
            Files.createDirectories(artifactDir);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create artifact dir: " + artifactDir, e);
        }
    }

    /** 保存大文本为 artifact，返回引用。 */
    public ArtifactRef save(String source, String content, String summary) {
        int seq = counter.incrementAndGet();
        String refId = String.format("art_%03d", seq);
        String fileName = refId + "_" + source + ".txt";
        Path filePath = artifactDir.resolve(fileName);

        try {
            Files.writeString(filePath, content);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write artifact: " + filePath, e);
        }

        ArtifactRef ref = ArtifactRef.of(refId, source, summary, content.length(), filePath.toString());
        refs.put(refId, ref);
        log.info("Artifact saved: {} ({} chars) -> {}", refId, content.length(), filePath);
        return ref;
    }

    /** 读取 artifact 全文。 */
    public String readContent(String refId) {
        ArtifactRef ref = refs.get(refId);
        if (ref == null) return null;
        try {
            return Files.readString(Path.of(ref.getFilePath()));
        } catch (IOException e) {
            log.error("Failed to read artifact: {}", refId, e);
            return null;
        }
    }

    public ArtifactRef get(String refId) { return refs.get(refId); }
    public List<ArtifactRef> listAll() { return new ArrayList<>(refs.values()); }

    /** 在 artifact 内容中搜索关键词。 */
    public String search(String refId, String pattern) {
        String content = readContent(refId);
        if (content == null) return "Artifact not found: " + refId;

        StringBuilder sb = new StringBuilder();
        String[] lines = content.split("\n");
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].toLowerCase().contains(pattern.toLowerCase())) {
                sb.append(String.format("Line %d: %s%n", i + 1, lines[i].trim()));
            }
        }
        return sb.length() > 0 ? sb.toString() : "No matches for pattern: " + pattern;
    }
}
