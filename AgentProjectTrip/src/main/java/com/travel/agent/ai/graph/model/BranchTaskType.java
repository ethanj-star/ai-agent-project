package com.travel.agent.ai.graph.model;

/**
 * 分支任务类型枚举。
 *
 * <p>系统架构位置：BranchDispatchNode -> <b>BranchTaskType</b> -> BranchAgentFacade</p>
 *
 * <p>职责：
 * <ul>
 *   <li>限定核心 Graph 允许分发的分支任务类型。</li>
 *   <li>为 BranchTask 和 BranchResult 提供统一的机器可读类型，避免依赖自然语言判断分支。</li>
 *   <li>后续新增预算、交通、签证等分支时，可以从这里扩展类型。</li>
 * </ul>
 * </p>
 */
public enum BranchTaskType {

    /** 天气分支，用于补充目的地天气或季节风险。 */
    WEATHER,

    /** 航班分支，用于查询入境或跨城航班价格参考。 */
    FLIGHT,

    /** 酒店分支，用于查询目的地住宿价格和评分参考。 */
    HOTEL,

    /** 景点分支，用于补充目的地热门景点和游玩线索。 */
    PLACES,

    /** 知识分支，用于补充攻略、防坑、交通经验等 RAG 信息。 */
    KNOWLEDGE
}
