package cn.kong.eon.store;

import cn.kong.eon.model.TodoItem;
import cn.kong.eon.model.TodoStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TodoStoreTest {

    @Test
    void replaceAll_clearsOldAndInsertsNew() {
        TodoStore store = new TodoStore();
        store.replaceAll(List.of(
                TodoItem.of("t1", "old task", "high")
        ), 1);

        List<TodoItem> result = store.replaceAll(List.of(
                TodoItem.of("t2", "new task", "medium"),
                TodoItem.of("t3", "another", "low")
        ), 2);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getId()).isEqualTo("t2");
        assertThat(result.get(1).getId()).isEqualTo("t3");
        assertThat(result).allMatch(t -> t.getLastModifiedTurn() == 2);
    }

    @Test
    void replaceAll_assignsIdToBlankIdItems() {
        TodoStore store = new TodoStore();
        TodoItem item = TodoItem.of(null, "no id", "medium");
        List<TodoItem> result = store.replaceAll(List.of(item), 1);
        assertThat(result.get(0).getId()).isNotBlank().startsWith("t");
    }

    @Test
    void mergeById_updatesExistingAndAppendsNew() {
        TodoStore store = new TodoStore();
        store.replaceAll(List.of(
                TodoItem.of("t1", "original", "high")
        ), 1);

        TodoItem updated = TodoItem.of("t1", "updated content", "low");
        updated.setStatus(TodoStatus.IN_PROGRESS);
        TodoItem newItem = TodoItem.of("t2", "brand new", "medium");

        List<TodoItem> result = store.mergeById(List.of(updated, newItem), 2);

        assertThat(result).hasSize(2);
        TodoItem t1 = result.stream().filter(t -> t.getId().equals("t1")).findFirst().orElseThrow();
        assertThat(t1.getContent()).isEqualTo("updated content");
        assertThat(t1.getStatus()).isEqualTo(TodoStatus.IN_PROGRESS);
    }

    @Test
    void validateSingleFocus_allowsAtMostOneInProgress() {
        TodoStore store = new TodoStore();
        List<TodoItem> oneInProgress = List.of(
                createWithStatus("t1", TodoStatus.IN_PROGRESS),
                createWithStatus("t2", TodoStatus.PENDING)
        );
        assertThat(store.validateSingleFocus(oneInProgress)).isTrue();

        List<TodoItem> twoInProgress = List.of(
                createWithStatus("t1", TodoStatus.IN_PROGRESS),
                createWithStatus("t2", TodoStatus.IN_PROGRESS)
        );
        assertThat(store.validateSingleFocus(twoInProgress)).isFalse();
    }

    @Test
    void formatProgress_countsByStatus() {
        List<TodoItem> todos = List.of(
                createWithStatus("t1", TodoStatus.COMPLETED),
                createWithStatus("t2", TodoStatus.COMPLETED),
                createWithStatus("t3", TodoStatus.IN_PROGRESS),
                createWithStatus("t4", TodoStatus.PENDING),
                createWithStatus("t5", TodoStatus.PENDING)
        );
        String progress = TodoStore.formatProgress(todos);
        assertThat(progress).contains("2/5 完成");
        assertThat(progress).contains("1 进行中");
        assertThat(progress).contains("2 待办");
        assertThat(progress).contains("0 阻塞");
    }

    @Test
    void formatProgress_emptyListReturnsEmpty() {
        assertThat(TodoStore.formatProgress(List.of())).isEmpty();
    }

    private TodoItem createWithStatus(String id, TodoStatus status) {
        TodoItem item = TodoItem.of(id, "task " + id, "medium");
        item.setStatus(status);
        return item;
    }
}
