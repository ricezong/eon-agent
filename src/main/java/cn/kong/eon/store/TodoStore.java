package cn.kong.eon.store;

import cn.kong.eon.model.TodoItem;
import cn.kong.eon.model.TodoStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Todo 存储。进程内存 Map，随 Checkpoint 落盘。
 * 核心语义：全量替换、单一焦点约束、永不物理删除。
 */
public class TodoStore {
    private static final Logger log = LoggerFactory.getLogger(TodoStore.class);

    private final Map<String, TodoItem> todos = new ConcurrentHashMap<>();

    /**
     * 全量替换 Todo 列表。
     * 注意：此方法不做校验（如单一焦点、依赖完整性），调用方需自行调用
     * {@link #validateSingleFocus} 和 {@link #validateDependencies} 进行校验。
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

    public TodoItem get(String id) { return todos.get(id); }
    public void clear() { todos.clear(); }
    public int size() { return todos.size(); }

    /** 校验单一焦点约束：同时最多一个 in_progress。 */
    public boolean validateSingleFocus(List<TodoItem> newTodos) {
        long inProgressCount = newTodos.stream()
                .filter(t -> t.getStatus() == TodoStatus.IN_PROGRESS)
                .count();
        return inProgressCount <= 1;
    }

    /** 校验依赖完整性：depends_on 未完成的 todo 不得标 in_progress。 */
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

    /** 检查是否所有 todo 都已完成（用于 finish 前校验）。 */
    public boolean allCompleted() {
        if (todos.isEmpty()) return true;
        return todos.values().stream().allMatch(t ->
                t.getStatus() == TodoStatus.COMPLETED || t.getStatus() == TodoStatus.CANCELLED);
    }

    // ===== 格式化方法 =====

    private static long countByStatus(List<TodoItem> todos, TodoStatus status) {
        return todos.stream().filter(t -> t.getStatus() == status).count();
    }

    /** 格式化 Todo 进度统计行，如 "2/5 完成 (1 进行中, 2 待办, 0 阻塞)"。 */
    public static String formatProgress(List<TodoItem> todos) {
        if (todos == null || todos.isEmpty()) return "";
        return countByStatus(todos, TodoStatus.COMPLETED) + "/" + todos.size()
                + " 完成 (" + countByStatus(todos, TodoStatus.IN_PROGRESS) + " 进行中, "
                + countByStatus(todos, TodoStatus.PENDING) + " 待办, "
                + countByStatus(todos, TodoStatus.BLOCKED) + " 阻塞)";
    }

    /** 格式化未完成任务列表（不含已完成和已取消）。 */
    public static String formatPending(List<TodoItem> todos) {
        if (todos == null || todos.isEmpty()) return "";
        List<TodoItem> pending = todos.stream()
                .filter(t -> t.getStatus() != TodoStatus.COMPLETED
                        && t.getStatus() != TodoStatus.CANCELLED)
                .collect(Collectors.toList());
        if (pending.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (TodoItem t : pending) {
            sb.append("  ").append(t.toString());
            if (t.getStatus() == TodoStatus.BLOCKED && t.getBlockReason() != null) {
                sb.append(" [阻塞: ").append(t.getBlockReason()).append("]");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private final AtomicInteger idCounter = new AtomicInteger(0);

    private String generateId() {
        return "t" + idCounter.incrementAndGet();
    }
}
