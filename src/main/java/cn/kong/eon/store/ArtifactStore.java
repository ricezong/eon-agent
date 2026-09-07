package cn.kong.eon.store;

import cn.kong.eon.agent.context.StoreSupport;
import cn.kong.eon.model.ArtifactRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Artifact 存储。将大文本工具结果落盘，上下文只保留引用。
 * 实现 {@link StoreSupport} 接口供入站管线调用。
 * <p>
 * refId 与文件名由消息序号确定性派生（{@code art_m00042_<source>.txt}）：
 * 一条消息至多产生一个工具结果块，序号即唯一键。不使用运行期计数器——
 * 计数器跨进程不连续，会话恢复的回放会从 0 重新编号，与磁盘上已有文件
 * 同名不同内容地错位覆盖。确定性命名下，回放与常规写入共用同一路径，
 * 重复落盘退化为同名同内容的幂等覆盖。
 */
public class ArtifactStore implements StoreSupport {
    private static final Logger log = LoggerFactory.getLogger(ArtifactStore.class);

    private final Path artifactDir;

    public ArtifactStore(Path artifactDir) {
        this.artifactDir = artifactDir;
        try {
            Files.createDirectories(artifactDir);
        } catch (IOException e) {
            throw new RuntimeException("创建 artifact 目录失败: " + artifactDir, e);
        }
    }

    /** 保存大文本为 artifact，返回引用。 */
    @Override
    public ArtifactRef save(String source, String content, int messageSeq) {
        String refId = String.format("art_m%05d", messageSeq);
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

    /**
     * 读取 artifact 全文。
     * <p>
     * 路径按 refId 在目录里反查（文件名带上来源工具后缀），不依赖运行期内存映射——
     * 恢复会话时窗口里只剩摘要，摘要中的 artifact 引用指向的是本轮未落盘过的文件。
     */
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

    /** 按 refId 前缀在 artifact 目录里定位文件（文件名 = refId_来源工具.txt）。 */
    private Path resolve(String refId) {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(artifactDir, refId + "_*.txt")) {
            for (Path p : stream) return p;
        } catch (IOException e) {
            log.error("查找 artifact 失败: {}", refId, e);
        }
        return null;
    }
}
