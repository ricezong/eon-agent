package cn.kong.eon.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryStoreTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper mapper = new ObjectMapper().registerModule(
            new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

    @Test
    void create_persistsMemory() {
        MemoryStore store = new MemoryStore(tempDir, mapper);
        var item = store.create("user preference", "prefers dark mode");

        assertThat(item.id).startsWith("mem_");
        assertThat(item.title).isEqualTo("user preference");
        assertThat(item.content).isEqualTo("prefers dark mode");
        assertThat(store.loadAll()).hasSize(1);
    }

    @Test
    void update_modifiesExistingMemory() {
        MemoryStore store = new MemoryStore(tempDir, mapper);
        var item = store.create("original title", "original content");

        var updated = store.update(item.id, "new title", "new content");

        assertThat(updated.title).isEqualTo("new title");
        assertThat(updated.content).isEqualTo("new content");

        var loaded = store.loadAll().get(0);
        assertThat(loaded.title).isEqualTo("new title");
    }

    @Test
    void update_throwsForNonExistentId() {
        MemoryStore store = new MemoryStore(tempDir, mapper);
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                store.update("mem_nonexistent", "t", "c")
        ).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void delete_removesMemory() {
        MemoryStore store = new MemoryStore(tempDir, mapper);
        var item = store.create("to delete", "content");

        boolean deleted = store.delete(item.id);
        assertThat(deleted).isTrue();
        assertThat(store.loadAll()).isEmpty();
    }

    @Test
    void delete_returnsFalseForNonExistent() {
        MemoryStore store = new MemoryStore(tempDir, mapper);
        assertThat(store.delete("mem_ghost")).isFalse();
    }

    @Test
    void renderForInjection_returnsEmptyStringWhenNoMemories() {
        MemoryStore store = new MemoryStore(tempDir, mapper);
        assertThat(store.renderForInjection()).isEmpty();
    }

    @Test
    void renderForInjection_formatsMemoriesAsBlock() {
        MemoryStore store = new MemoryStore(tempDir, mapper);
        store.create("pref1", "likes Python");
        store.create("pref2", "uses VS Code");

        String injection = store.renderForInjection();
        assertThat(injection).contains("<memories>");
        assertThat(injection).contains("pref1");
        assertThat(injection).contains("pref2");
        assertThat(injection).contains("</memories>");
    }

    @Test
    void renderReferences_replacesMemoryReferences() {
        MemoryStore store = new MemoryStore(tempDir, mapper);
        var item = store.create("GitHub", "uses GitHub for version control");

        String text = "Check [[memory:" + item.id + "]] for details.";
        String rendered = store.renderReferences(text);

        assertThat(rendered).contains("GitHub");
        assertThat(rendered).doesNotContain("[[memory:");
    }

    @Test
    void renderReferences_preservesUnknownReferences() {
        MemoryStore store = new MemoryStore(tempDir, mapper);
        String text = "See [[memory:mem_ghost]] for info.";
        String rendered = store.renderReferences(text);
        assertThat(rendered).isEqualTo(text);
    }
}
