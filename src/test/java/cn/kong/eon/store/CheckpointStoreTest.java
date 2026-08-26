package cn.kong.eon.store;

import cn.kong.eon.model.Checkpoint;
import cn.kong.eon.model.CompressionState;
import cn.kong.eon.model.TodoItem;
import cn.kong.eon.model.TokenUsage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CheckpointStoreTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void save_createsCheckpointFile() {
        CheckpointStore store = new CheckpointStore(tempDir, mapper);

        List<TodoItem> todos = List.of(TodoItem.of("t1", "task 1", "high"));
        TokenUsage usage = new TokenUsage();
        usage.setTotalTokens(5000);
        CompressionState compState = new CompressionState();

        Checkpoint cp = store.save("eon_test", 3, todos, usage, compState);

        assertThat(cp.getCheckpointId()).startsWith("cp_");
        assertThat(cp.getSessionId()).isEqualTo("eon_test");
        assertThat(cp.getTurnCount()).isEqualTo(3);
        assertThat(cp.getTodoSnapshot()).hasSize(1);
        assertThat(cp.getUsageAccum().getTotalTokens()).isEqualTo(5000);
        assertThat(java.nio.file.Files.exists(tempDir.resolve(cp.getCheckpointId() + ".json"))).isTrue();
    }

    @Test
    void save_incrementsIdSequence() {
        CheckpointStore store = new CheckpointStore(tempDir, mapper);

        Checkpoint cp1 = store.save("s1", 1, List.of(), new TokenUsage(), new CompressionState());
        Checkpoint cp2 = store.save("s1", 2, List.of(), new TokenUsage(), new CompressionState());

        assertThat(cp1.getCheckpointId()).isNotEqualTo(cp2.getCheckpointId());
    }
}
