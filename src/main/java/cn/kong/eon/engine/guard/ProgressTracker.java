package cn.kong.eon.engine.guard;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

/**
 * Todo 无进展检测器：滑动窗口比对 Todo 快照，连续多个窗口无变化则判定无进展。
 * <p>
 * 原为 {@code TodoNoProgressHook} 的私有状态，外置后挂在 {@code TaskScope} 上，
 * 使 Hook 可以变无状态单例，避免多会话并发时互相污染计数。
 */
public final class ProgressTracker {

    private final int windowSize;

    private final Deque<String> snapshots = new ArrayDeque<>();
    private int stepsWithoutProgress = 0;

    public ProgressTracker(int windowSize) {
        this.windowSize = windowSize;
    }

    public int windowSize() {
        return windowSize;
    }

    /**
     * 压入一份 Todo 快照并判定进展。
     *
     * @return 连续无进展的窗口数（0 表示有进展或窗口未满）
     */
    public int pushAndCheck(String snapshot) {
        snapshots.addLast(snapshot);
        if (snapshots.size() > windowSize) {
            snapshots.removeFirst();
        }

        if (snapshots.size() == windowSize) {
            Set<String> unique = new HashSet<>(snapshots);
            if (unique.size() == 1) {
                stepsWithoutProgress++;
            } else {
                stepsWithoutProgress = 0;
            }
        }
        return stepsWithoutProgress;
    }
}
