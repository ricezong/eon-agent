package cn.kong.eon.tool;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 路径解析器。统一处理相对路径/绝对路径解析和沙箱校验。
 * - 相对路径基于 workspace 解析
 * - 绝对路径直接使用（沙箱开启时仍需在工作区内）
 * - 禁止 `..` 穿越逃逸
 */
public class PathResolver {

    private final String workDir;
    private final boolean sandboxEnabled;

    public PathResolver(String workDir, boolean sandboxEnabled) {
        this.workDir = workDir != null ? workDir : System.getProperty("user.dir");
        this.sandboxEnabled = sandboxEnabled;
    }

    /**
     * 解析路径为绝对路径。
     * @param rawPath 用户传入的路径（相对或绝对）
     * @return 解析后的绝对路径
     * @throws IllegalArgumentException 路径穿越沙箱边界时抛出
     */
    public Path resolve(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            throw new IllegalArgumentException("Path is empty");
        }

        Path resolved;
        Path workspace = Path.of(workDir).toAbsolutePath().normalize();

        // 绝对路径直接使用
        if (Path.of(rawPath).isAbsolute()) {
            resolved = Path.of(rawPath).toAbsolutePath().normalize();
        } else {
            // 相对路径基于 workspace 解析
            resolved = workspace.resolve(rawPath).toAbsolutePath().normalize();
        }

        // 沙箱校验：解析后路径必须以 workspace 开头
        if (sandboxEnabled && !resolved.startsWith(workspace)) {
            throw new IllegalArgumentException(
                    "Path '" + rawPath + "' escapes workspace boundary (resolved: " + resolved + ")");
        }

        return resolved;
    }

    /** 获取 workspace 根路径。 */
    public Path workspace() {
        return Path.of(workDir).toAbsolutePath().normalize();
    }

    /** 检查路径是否存在。 */
    public boolean exists(String rawPath) {
        try {
            return Files.exists(resolve(rawPath));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
