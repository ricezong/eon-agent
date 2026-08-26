package cn.kong.eon.store;

import cn.kong.eon.model.ArtifactRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Artifact 存储。大文本工具结果落盘，上下文只保留引用。
 */
public class ArtifactStore {
    private static final Logger log = LoggerFactory.getLogger(ArtifactStore.class);

    private final Path artifactDir;
    private final Map<String, ArtifactRef> refs = new ConcurrentHashMap<>();  // 引用注册表
    private final AtomicInteger counter = new AtomicInteger(0); // 自增 ID 计数器

    public ArtifactStore(Path artifactDir) {
        this.artifactDir = artifactDir;
        try {
            Files.createDirectories(artifactDir);
        } catch (IOException e) {
            throw new RuntimeException("创建 artifact 目录失败: " + artifactDir, e);
        }
    }

    /**
     * 保存大文本为 artifact，返回引用。
     */
    public ArtifactRef save(String source, String content, String summary) {
        int seq = counter.incrementAndGet();
        String refId = String.format("art_%03d", seq);
        String fileName = refId + "_" + source + ".txt";
        Path filePath = artifactDir.resolve(fileName);

        try {
            Files.writeString(filePath, content);
        } catch (IOException e) {
            throw new RuntimeException("写入 artifact 失败: " + filePath, e);
        }

        ArtifactRef ref = ArtifactRef.of(refId, source, summary, content.length(), filePath.toString());
        refs.put(refId, ref);
        log.info("Artifact 已保存: {} ({} 字符) -> {}", refId, content.length(), filePath);
        return ref;
    }

    /**
     * 读取 artifact 全文。
     */
    public String readContent(String refId) {
        ArtifactRef ref = refs.get(refId);
        if (ref == null) return null;
        try {
            return Files.readString(Path.of(ref.getFilePath()));
        } catch (IOException e) {
            log.error("读取 artifact 失败: {}", refId, e);
            return null;
        }
    }
}
