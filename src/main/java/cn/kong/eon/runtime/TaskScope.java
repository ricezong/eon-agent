package cn.kong.eon.runtime;

import cn.kong.eon.config.AgentConfig;
import cn.kong.eon.engine.guard.LoopDetector;
import cn.kong.eon.engine.guard.ProgressTracker;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 任务级作用域：一次 run 创建，结束丢弃。
 * <p>
 * 装的是「这一次用户请求」范围内有效、但跨轮累计的东西：
 * 用户输入、轮次计数、nudges、中断标志，以及两个从 Hook 外置过来的检测器。
 */
public final class TaskScope {

    private final String userInput;
    private final String turnId;
    private final List<String> nudges = new ArrayList<>();
    private final LoopDetector loopDetector;
    private final ProgressTracker progressTracker;

    private int turnCount;
    private volatile boolean interrupted;

    public TaskScope(String userInput, AgentConfig.LoopDetectConfig loopCfg) {
        this.userInput = userInput;
        this.turnId = "turn_" + UUID.randomUUID().toString().substring(0, 8);
        this.loopDetector = new LoopDetector(loopCfg.getRepeatWarn(), loopCfg.getRepeatStop());
        this.progressTracker = new ProgressTracker(loopCfg.getNoProgressSteps());
    }

    /** 递增轮次，返回递增后的轮次序号（1 起）。 */
    public int incrementTurn() {
        return ++turnCount;
    }

    public int turnCount() {
        return turnCount;
    }

    public void addNudge(String nudge) {
        nudges.add(nudge);
    }

    /** 请求中断当前任务。 */
    public void requestInterrupt() {
        this.interrupted = true;
    }

    public boolean isInterrupted() {
        return interrupted;
    }

    public String userInput() {
        return userInput;
    }

    public String turnId() {
        return turnId;
    }

    public List<String> nudges() {
        return nudges;
    }

    public LoopDetector loopDetector() {
        return loopDetector;
    }

    public ProgressTracker progressTracker() {
        return progressTracker;
    }
}
