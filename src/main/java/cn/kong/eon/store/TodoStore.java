package cn.kong.eon.store;

import cn.kong.eon.model.TodoItem;
import cn.kong.eon.model.TodoStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Todo 存储。
 * 对应技术方案第 2.1 节。
 * 运行态：进程内存 Map（并发安全）。
 * 持久化：随 Checkpoint 落盘。
 * 核心语义：全量替换、单一焦点约束、永不物理删除。
 */
public class TodoStore {
    private static final Logger log = LoggerFactory.getLogger(TodoStore.class);

    private final Map<String, TodoItem> todos = new ConcurrentHashMap<>();

    /**
     * 全量替换 Todo 列表。
     * 传入什么就是什么，不做 diff/patch。
     */
    public synchronized List<TodoItem> replaceAll(List<TodoItem> newTodos, int currentTurn) {
        todos.clear();
        for (TodoItem t : newTodos) {
            if (t.getId() == null || t.getId().isBlank()) {
                t.setId(generateId());
            }
            t.setLastModifiedTurn(currentTurn);
            todos.put(t.getId(), t);
        }
        log.debug("TodoStore replaced: {} items", todos.size());
        return getAll();
    }

    public List<TodoItem> getAll() {
        List<TodoItem> list = new ArrayList<>(todos.values());
        list.sort(Comparator.comparing(TodoItem::getId));
        return list;
    }

    public TodoItem get(String id) {
        return todos.get(id);
    }

    public void clear() {
        todos.clear();
    }

    public int size() {
        return todos.size();
    }

    /**
     * 校验单一焦点约束：同时最多一个 in_progress。
     */
    public boolean validateSingleFocus(List<TodoItem> newTodos) {
        long inProgressCount = newTodos.stream()
                .filter(t -> t.getStatus() == TodoStatus.IN_PROGRESS)
                .count();
        return inProgressCount <= 1;
    }

    /**
     * 校验依赖完整性：depends_on 未完成的 todo 不得标 in_progress。
     */
    public boolean validateDependencies(List<TodoItem> newTodos) {
        Map<String, TodoStatus> statusMap = new HashMap<>();
        for (TodoItem t : newTodos) {
            statusMap.put(t.getId(), t.getStatus());
        }
        for (TodoItem t : newTodos) {
            if (t.getStatus() == TodoStatus.IN_PROGRESS && t.getDependsOn() != null) {
                for (String depId : t.getDependsOn()) {
                    TodoStatus depStatus = statusMap.get(depId);
                    if (depStatus != null && depStatus != TodoStatus.COMPLETED) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /**
     * 检查是否所有 todo 都已完成（用于 finish 前校验）。
     */
    public boolean allCompleted() {
        if (todos.isEmpty()) return true;
        return todos.values().stream().allMatch(t ->
                t.getStatus() == TodoStatus.COMPLETED || t.getStatus() == TodoStatus.CANCELLED);
    }

    private String generateId() {
        return "t" + System.currentTimeMillis() % 100000;
    }
}
