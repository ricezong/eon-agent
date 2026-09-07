package cn.kong.eon.tool;

import java.nio.file.Path;

/**
 * 路径解析器。相对路径基于 workspace 解析，绝对路径直接使用；沙箱开启时须在工作区内；禁止 .. 穿越。
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
     * @throws IllegalArgumentException 路径穿越沙箱边界时抛出
     */
    public Path resolve(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            throw new IllegalArgumentException("路径为空");
        }

        Path resolved;
        Path workspace = Path.of(workDir).toAbsolutePath().normalize();

        if (Path.of(rawPath).isAbsolute()) {
            resolved = Path.of(rawPath).toAbsolutePath().normalize();
        } else {
            resolved = workspace.resolve(rawPath).toAbsolutePath().normalize();
        }

        // 沙箱校验：解析后路径必须以 workspace 开头
        if (sandboxEnabled && !resolved.startsWith(workspace)) {
            throw new IllegalArgumentException(
                    "路径 '" + rawPath + "' 超出工作区边界 (解析后: " + resolved + ")");
        }

        return resolved;
    }
}
