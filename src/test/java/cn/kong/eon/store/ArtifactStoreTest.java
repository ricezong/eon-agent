package cn.kong.eon.store;

import cn.kong.eon.model.ArtifactRef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ArtifactStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void save_createsFileAndReturnsRef() {
        ArtifactStore store = new ArtifactStore(tempDir);
        String content = "x".repeat(5000);

        ArtifactRef ref = store.save("read_file", content, "large file content");

        assertThat(ref.getRefId()).isNotBlank();
        assertThat(ref.getSizeChars()).isEqualTo(5000);
        assertThat(ref.getSource()).isEqualTo("read_file");
        assertThat(ref.getSummary()).isEqualTo("large file content");
        assertThat(java.nio.file.Files.exists(Path.of(ref.getFilePath()))).isTrue();
    }

    @Test
    void save_incrementsIdSequence() {
        ArtifactStore store = new ArtifactStore(tempDir);

        ArtifactRef ref1 = store.save("src1", "content1", "s1");
        ArtifactRef ref2 = store.save("src2", "content2", "s2");

        assertThat(ref1.getRefId()).isNotEqualTo(ref2.getRefId());
    }

    @Test
    void readContent_returnsSavedContent() {
        ArtifactStore store = new ArtifactStore(tempDir);
        String content = "test artifact content";
        ArtifactRef ref = store.save("test", content, "test summary");

        String read = store.readContent(ref.getRefId());

        assertThat(read).isEqualTo(content);
    }

    @Test
    void readContent_returnsNullForUnknownId() {
        ArtifactStore store = new ArtifactStore(tempDir);
        assertThat(store.readContent("art_999")).isNull();
    }
}
