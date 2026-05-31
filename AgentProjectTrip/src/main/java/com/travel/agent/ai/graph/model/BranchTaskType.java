package com.travel.agent.ai.graph.model;

/**
 * 分支任务类型枚举。
 *
 * <p>系统架构位置：BranchDispatchNode -> <b>BranchTaskType</b> -> BranchAgentFacade</p>
 *
 * <p>职责：
 * <ul>
 *   <li>限定第三阶段第一版允许核心 Graph 分发的分支任务类型。</li>
 *   <li>为 BranchTask 和 BranchResult 提供统一的机器可读类型，避免依赖自然语言判断分支。</li>
 *   <li>后续新增酒店、预算、签证等分支时，可以从这里扩展类型。</li>
 * </ul>
 * </p>
 */
public enum BranchTaskType {

    /** 天气分支，用于补充目的地天气或季节风险。 */
    WEATHER,

    /** 航班分支，第一版保留协议，真实查询后续接入。 */
    FLIGHT,

    /** 景点分支，用于补充目的地热门景点和游玩线索。 */
    PLACES,

    /** 知识分支，用于补充攻略、防坑、交通经验等 RAG 信息。 */
    KNOWLEDGE
}
