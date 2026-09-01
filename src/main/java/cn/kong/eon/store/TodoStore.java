package cn.kong.eon.store;

import cn.kong.eon.model.TodoItem;
import cn.kong.eon.model.TodoStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Todo 存储。进程内存 Map，随 Checkpoint 落盘。
 * 支持全量替换、按 id 合并和单一焦点校验。
 */
public class TodoStore {
    private static final Logger log = LoggerFactory.getLogger(TodoStore.class);

    private final Map<String, TodoItem> todos = new ConcurrentHashMap<>();
    private final AtomicInteger idCounter = new AtomicInteger(0);

    /**
     * 全量替换 Todo 列表，清空后重新填充。
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
        log.debug("TodoStore 全量替换: {} 个条目", todos.size());
        return getAll();
    }

    /**
     * 按 id 合并 Todo 列表。已存在 id 更新内容/状态，新 id 追加。
     */
    public synchronized List<TodoItem> mergeById(List<TodoItem> newTodos, int currentTurn) {
        for (TodoItem t : newTodos) {
            if (t.getId() == null || t.getId().isBlank()) {
                t.setId(generateId());
            }
            t.setLastModifiedTurn(currentTurn);
            todos.put(t.getId(), t);
        }
        log.debug("TodoStore 合并: 共 {} 个条目", todos.size());
        return getAll();
    }

    /** 获取所有 Todo（按 id 排序）。 */
    public List<TodoItem> getAll() {
        List<TodoItem> list = new ArrayList<>(todos.values());
        list.sort(Comparator.comparing(TodoItem::getId));
        return list;
    }

    /** 校验单一焦点约束：同时最多一个 in_progress。 */
    public boolean validateSingleFocus(List<TodoItem> newTodos) {
        long inProgressCount = newTodos.stream()
                .filter(t -> t.getStatus() == TodoStatus.IN_PROGRESS)
                .count();
        return inProgressCount <= 1;
    }

    /** 按状态统计数量。 */
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

    /** 生成自增 id。 */
    private String generateId() {
        return "t" + idCounter.incrementAndGet();
    }
}
