package cn.kong.eon.store.artifact;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 工具结果存储。大文本工具结果落盘，上下文只保留引用。
 * refId 与文件名由消息序号确定性派生（tool-result_00042_read_file.txt），
 * 回放与常规写入共用路径，重复落盘为幂等覆盖。
 */
public class ArtifactStore {
    private static final Logger log = LoggerFactory.getLogger(ArtifactStore.class);

    private final Path artifactDir;

    public ArtifactStore(Path artifactDir) {
        this.artifactDir = artifactDir;
    }

    /** 保存大文本为 artifact，返回引用。 */
    public ArtifactRef save(String source, String content, int messageSeq) {
        String refId = String.format("tool-result_%05d", messageSeq);
        Path filePath = artifactDir.resolve(refId + "_" + source + ".txt");

        try {
            Files.writeString(filePath, content);
        } catch (IOException e) {
            log.error("写入 artifact 失败，本次落盘不可用: {}", filePath, e);
            return null;
        }

        log.info("Artifact 已保存: {} ({} 字符) -> {}", refId, content.length(), filePath);
        return new ArtifactRef(refId, filePath.toString());
    }

    /** 按 refId 读取 artifact 全文。路径按 refId 在目录反查，不依赖运行期内存映射。 */
    public String readContent(String refId) {
        Path filePath = resolve(refId);
        if (filePath == null) return null;
        try {
            return Files.readString(filePath);
        } catch (IOException e) {
            log.error("读取 artifact 失败: {}", refId, e);
            return null;
        }
    }

    /** 按 refId 前缀在 artifact 目录里定位文件。 */
    private Path resolve(String refId) {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(artifactDir, refId + "_*.txt")) {
            for (Path p : stream) return p;
        } catch (IOException e) {
            log.error("查找 artifact 失败: {}", refId, e);
        }
        return null;
    }
}
