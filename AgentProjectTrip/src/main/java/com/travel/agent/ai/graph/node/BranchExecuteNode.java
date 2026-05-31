package com.travel.agent.ai.graph.node;

import com.travel.agent.ai.agents.BranchAgentFacade;
import com.travel.agent.ai.graph.model.BranchResult;
import com.travel.agent.ai.graph.model.BranchTask;
import com.travel.agent.ai.graph.model.TravelPlanState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 分支任务执行节点。
 *
 * <p>系统架构位置：BranchDispatchNode -> <b>BranchExecuteNode</b> -> BranchAgentFacade</p>
 *
 * <p>职责：
 * <ul>
 *   <li>读取 TravelPlanState.branchTasks。</li>
 *   <li>通过 {@link BranchAgentFacade} 顺序执行分支任务。</li>
 *   <li>把所有结果写回 TravelPlanState.branchResults，供 Planner 生成方案时使用。</li>
 * </ul>
 * </p>
 *
 * <p>第三阶段第一版不做并行，目的是保证日志顺序、测试稳定和异常定位清晰。</p>
 */
@Component
public class BranchExecuteNode {

    private static final Logger log = LoggerFactory.getLogger(BranchExecuteNode.class);

    private final BranchAgentFacade branchAgentFacade;

    /**
     * 构造器注入分支 Agent 门面。
     *
     * @param branchAgentFacade 统一执行天气、景点、知识等分支任务的门面
     */
    public BranchExecuteNode(BranchAgentFacade branchAgentFacade) {
        this.branchAgentFacade = branchAgentFacade;
    }

    /**
     * 顺序执行当前状态中的分支任务。
     *
     * @param state 当前旅行规划状态
     * @return 写入 branchResults 后的状态
     */
    public TravelPlanState execute(TravelPlanState state) {
        if (state == null) {
            TravelPlanState fallback = new TravelPlanState();
            fallback.setBranchResults(new ArrayList<>());
            return fallback;
        }
        List<BranchTask> tasks = state.getBranchTasks();
        if (tasks == null || tasks.isEmpty()) {
            state.setBranchResults(new ArrayList<>());
            log.info("[Graph][BranchExecute] no branch tasks");
            return state;
        }

        List<BranchResult> results = new ArrayList<>();
        for (BranchTask task : tasks) {
            results.add(branchAgentFacade.execute(task));
        }
        state.setBranchResults(results);
        log.info("[Graph][BranchExecute] results={}", results.size());
        return state;
    }
}
