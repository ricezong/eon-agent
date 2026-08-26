package cn.kong.eon.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PathResolverTest {

    @TempDir
    Path tempDir;

    @Test
    void resolve_relativePath_resolvesAgainstWorkDir() {
        PathResolver resolver = new PathResolver(tempDir.toString(), true);
        var resolved = resolver.resolve("subdir/file.txt");

        // Use string comparison to avoid Path.startsWith requiring file existence
        assertThat(resolved.toString()).startsWith(tempDir.toString());
        assertThat(resolved.getFileName().toString()).isEqualTo("file.txt");
        assertThat(resolved.getParent().getFileName().toString()).isEqualTo("subdir");
    }

    @Test
    void resolve_absolutePathWithinSandbox_passes() {
        PathResolver resolver = new PathResolver(tempDir.toString(), true);
        // Use the tempDir itself as an absolute path (it exists)
        var resolved = resolver.resolve(tempDir.toString());

        assertThat(resolved.toString()).startsWith(tempDir.toString());
    }

    @Test
    void resolve_parentTraversal_throwsInSandbox() {
        PathResolver resolver = new PathResolver(tempDir.toString(), true);
        assertThatThrownBy(() -> resolver.resolve("../../etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("escapes workspace boundary");
    }

    @Test
    void resolve_parentTraversal_allowedWhenSandboxDisabled() {
        PathResolver resolver = new PathResolver(tempDir.toString(), false);
        var resolved = resolver.resolve("../sibling/file.txt");
        // Should NOT throw; path may be outside workspace
        assertThat(resolved).isNotNull();
    }

    @Test
    void resolve_emptyPath_throws() {
        PathResolver resolver = new PathResolver(tempDir.toString(), true);
        assertThatThrownBy(() -> resolver.resolve(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty");
    }

    @Test
    void resolve_nullPath_throws() {
        PathResolver resolver = new PathResolver(tempDir.toString(), true);
        assertThatThrownBy(() -> resolver.resolve(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty");
    }

    @Test
    void resolve_blankPath_throws() {
        PathResolver resolver = new PathResolver(tempDir.toString(), true);
        assertThatThrownBy(() -> resolver.resolve("   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty");
    }

    @Test
    void resolve_normalizesPath() {
        PathResolver resolver = new PathResolver(tempDir.toString(), true);
        var resolved = resolver.resolve("a/./b/../c/file.txt");
        // Normalized path should be workspace/a/c/file.txt
        assertThat(resolved.toString()).startsWith(tempDir.toString());
        assertThat(resolved.getFileName().toString()).isEqualTo("file.txt");
        assertThat(resolved.getParent().getFileName().toString()).isEqualTo("c");
        assertThat(resolved.getParent().getParent().getFileName().toString()).isEqualTo("a");
    }

    @Test
    void resolve_absolutePathOutsideSandbox_throws() {
        PathResolver resolver = new PathResolver(tempDir.toString(), true);
        assertThatThrownBy(() -> resolver.resolve("/etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("escapes workspace boundary");
    }

    @Test
    void resolve_nullWorkDir_defaultsToUserDir() {
        PathResolver resolver = new PathResolver(null, false);
        var resolved = resolver.resolve("test.txt");
        assertThat(resolved).isAbsolute();
    }
}
