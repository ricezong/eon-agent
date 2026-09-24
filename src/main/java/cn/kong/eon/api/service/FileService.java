package cn.kong.eon.api.service;

import cn.kong.eon.config.AgentConfig;
import cn.kong.eon.tool.PathResolver;
import cn.kong.eon.api.dto.FileContent;
import cn.kong.eon.api.dto.FileMeta;
import cn.kong.eon.api.exception.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 会话工作区文件访问。路径一律经 {@link PathResolver} 解析，越界即拒绝，
 * 保证接口无法读到会话目录之外的文件。
 * <p>
 * 只提供事实（体积 / MIME / 是否二进制 / 编码），不做渲染决策。
 */
@Service
public class FileService {

    private static final Logger log = LoggerFactory.getLogger(FileService.class);

    /** 文本预览的字节上限：超出只取前 N 字节，避免大文件整体进内存。 */
    private static final int PREVIEW_LIMIT_BYTES = 200 * 1024;

    /** 二进制探测窗口。 */
    private static final int SNIFF_BYTES = 8192;

    /** 截断时多读的字节数，用于回退到完整的多字节字符边界。 */
    private static final int DECODE_SLACK_BYTES = 8;

    private static final String DEFAULT_MIME = "application/octet-stream";

    /** 扩展名 → MIME。仅用于 {@code Files.probeContentType} 探测失败时兜底。 */
    private static final Map<String, String> EXT_MIME = Map.ofEntries(
            Map.entry("html", "text/html"), Map.entry("htm", "text/html"),
            Map.entry("css", "text/css"), Map.entry("js", "text/javascript"),
            Map.entry("mjs", "text/javascript"), Map.entry("ts", "text/typescript"),
            Map.entry("json", "application/json"), Map.entry("md", "text/markdown"),
            Map.entry("markdown", "text/markdown"), Map.entry("txt", "text/plain"),
            Map.entry("csv", "text/csv"), Map.entry("xml", "application/xml"),
            Map.entry("yml", "text/yaml"), Map.entry("yaml", "text/yaml"),
            Map.entry("svg", "image/svg+xml"), Map.entry("png", "image/png"),
            Map.entry("jpg", "image/jpeg"), Map.entry("jpeg", "image/jpeg"),
            Map.entry("gif", "image/gif"), Map.entry("webp", "image/webp"),
            Map.entry("ico", "image/x-icon"), Map.entry("pdf", "application/pdf"),
            Map.entry("py", "text/x-python"), Map.entry("java", "text/x-java"),
            Map.entry("sql", "text/x-sql"), Map.entry("sh", "text/x-sh"),
            Map.entry("vue", "text/html")
    );

    /** 解码候选顺序：UTF-8 优先，GBK 兜中文 Windows 产物，ISO-8859-1 保底。 */
    private static final List<String> CHARSETS = List.of("UTF-8", "GBK", "ISO-8859-1");

    private final AgentConfig config;

    public FileService(AgentConfig config) {
        this.config = config;
    }

    /** 文件句柄：供 raw 端点输出字节流。 */
    public record FileHandle(Path file, FileMeta meta) {
    }

    /** 文件元信息。 */
    public FileMeta meta(String sessionId, String rawPath) {
        Located loc = locate(sessionId, rawPath);
        return toMeta(loc.file(), loc.resolver());
    }

    /** 文件文本预览，超过上限时截断。 */
    public FileContent content(String sessionId, String rawPath) {
        Located loc = locate(sessionId, rawPath);
        Path file = loc.file();

        long size = sizeOf(file);
        boolean truncated = size > PREVIEW_LIMIT_BYTES;
        int toRead = (int) Math.min(size, PREVIEW_LIMIT_BYTES + DECODE_SLACK_BYTES);
        byte[] buf = readHead(file, toRead);

        Decoded decoded = decode(buf);
        FileMeta meta = new FileMeta(
                file.getFileName().toString(),
                loc.resolver().relativize(file),
                size,
                detectMime(file),
                isBinary(buf, decoded),
                decoded.encoding(),
                modifiedAt(file));

        return new FileContent(meta, decoded.text(), truncated, PREVIEW_LIMIT_BYTES);
    }

    /** 原始文件句柄，供下载或内联展示。 */
    public FileHandle raw(String sessionId, String rawPath) {
        Located loc = locate(sessionId, rawPath);
        return new FileHandle(loc.file(), toMeta(loc.file(), loc.resolver()));
    }

    // ═══════════════ 内部实现 ═══════════════

    private record Located(Path file, PathResolver resolver) {
    }

    /** 定位文件：会话目录不存在、文件缺失、非普通文件、路径越界都会抛异常。 */
    private Located locate(String sessionId, String rawPath) {
        Path base = Path.of(config.getStorage().getBaseDir()).toAbsolutePath().normalize();
        Path sessionDir = base.resolve(sessionId);
        if (!Files.isDirectory(sessionDir)) {
            throw ApiException.sessionNotFound(sessionId);
        }

        Path workDir = sessionDir.resolve("download").toAbsolutePath().normalize();
        PathResolver resolver = new PathResolver(
                workDir.toString(),
                sessionDir.toAbsolutePath().normalize().toString(),
                config.getTools().isSandboxEnabled());

        Path file;
        try {
            file = resolver.resolve(rawPath);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("路径不合法: " + e.getMessage());
        }

        if (!Files.exists(file)) {
            throw ApiException.fileNotFound("文件不存在: " + rawPath);
        }
        if (!Files.isRegularFile(file)) {
            throw ApiException.fileNotFound("不是普通文件: " + rawPath);
        }
        return new Located(file, resolver);
    }

    private FileMeta toMeta(Path file, PathResolver resolver) {
        byte[] head = readHead(file, SNIFF_BYTES);
        Decoded decoded = decode(head);
        return new FileMeta(
                file.getFileName().toString(),
                resolver.relativize(file),
                sizeOf(file),
                detectMime(file),
                isBinary(head, decoded),
                decoded.encoding(),
                modifiedAt(file));
    }

    /** 二进制判定：含 NUL 字节，或所有候选字符集都无法解码。 */
    private static boolean isBinary(byte[] head, Decoded decoded) {
        if ("unknown".equals(decoded.encoding())) return true;
        for (byte b : head) {
            if (b == 0) return true;
        }
        return false;
    }

    /**
     * 按候选字符集解码。截断可能切断多字节字符，故逐字节回退重试，
     * 优先接受 UTF-8 的结果，避免中文文件被误判成 GBK 而乱码。
     */
    private static Decoded decode(byte[] buf) {
        Decoded fallback = null;
        int minLen = Math.max(1, buf.length - DECODE_SLACK_BYTES);
        for (int len = buf.length; len >= minLen; len--) {
            Decoded d = decodeStrict(buf, len);
            if ("UTF-8".equals(d.encoding())) return d;
            if (fallback == null) fallback = d;
        }
        return fallback;
    }

    private static Decoded decodeStrict(byte[] buf, int length) {
        for (String name : CHARSETS) {
            Charset charset = charsetOf(name);
            if (charset == null) continue;
            try {
                CharBuffer cb = charset.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(buf, 0, length));
                return new Decoded(cb.toString(), charset.name());
            } catch (CharacterCodingException | IllegalArgumentException ignored) {
                // 换下一个字符集
            }
        }
        return new Decoded(new String(buf, 0, length, StandardCharsets.ISO_8859_1), "unknown");
    }

    private static Charset charsetOf(String name) {
        try {
            return Charset.forName(name);
        } catch (Exception e) {
            log.warn("字符集不可用: {}", name);
            return null;
        }
    }

    private record Decoded(String text, String encoding) {
    }

    /** 只读前 N 字节，大文件不会整体进内存。 */
    private static byte[] readHead(Path file, int max) {
        try (InputStream in = Files.newInputStream(file)) {
            return in.readNBytes(max);
        } catch (IOException e) {
            log.warn("读取文件失败 {}: {}", file, e.getMessage());
            return new byte[0];
        }
    }

    private static String detectMime(Path file) {
        try {
            String probed = Files.probeContentType(file);
            if (probed != null && !probed.isBlank()) return probed;
        } catch (IOException ignored) {
            // 探测失败走扩展名兜底
        }
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot > 0 && dot < name.length() - 1) {
            return EXT_MIME.getOrDefault(name.substring(dot + 1).toLowerCase(), DEFAULT_MIME);
        }
        return DEFAULT_MIME;
    }

    private static long sizeOf(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return 0;
        }
    }

    private static String modifiedAt(Path file) {
        try {
            FileTime t = Files.getLastModifiedTime(file);
            return Instant.ofEpochMilli(t.toMillis()).toString();
        } catch (IOException e) {
            return null;
        }
    }
}
