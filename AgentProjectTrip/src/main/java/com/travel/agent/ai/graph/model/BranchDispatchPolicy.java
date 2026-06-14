package com.travel.agent.ai.graph.model;

import java.util.EnumSet;
import java.util.Set;

/**
 * 分支派发 Guard 的安全策略。
 *
 * <p>系统架构位置：BranchDispatchGuardNode -> <b>BranchDispatchPolicy</b></p>
 *
 * <p>职责：
 * <ul>
 *   <li>集中定义当前系统允许模型建议的分支任务类型。</li>
 *   <li>限制单次 Graph 运行最多执行多少分支任务，避免外部 API 调用失控。</li>
 *   <li>把“模型理解”和“系统可执行边界”分开，后续新增工具时只需要扩展策略。</li>
 * </ul>
 * </p>
 */
public class BranchDispatchPolicy {

    /** 第一版允许派发的真实或稳定分支。 */
    private final Set<BranchTaskType> allowedTypes;

    /** 单次规划最多允许的分支任务数。 */
    private final int maxTaskCount;

    private BranchDispatchPolicy(Set<BranchTaskType> allowedTypes, int maxTaskCount) {
        this.allowedTypes = EnumSet.copyOf(allowedTypes);
        this.maxTaskCount = Math.max(1, maxTaskCount);
    }

    public static BranchDispatchPolicy defaultPolicy() {
        return new BranchDispatchPolicy(
                EnumSet.of(
                        BranchTaskType.KNOWLEDGE,
                        BranchTaskType.WEATHER,
                        BranchTaskType.PLACES,
                        BranchTaskType.FLIGHT,
                        BranchTaskType.HOTEL),
                5);
    }

    public boolean isAllowedType(BranchTaskType type) {
        return type != null && allowedTypes.contains(type);
    }

    public int maxTaskCount() {
        return maxTaskCount;
    }

    public Set<BranchTaskType> allowedTypes() {
        return EnumSet.copyOf(allowedTypes);
    }
}
