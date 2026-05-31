package com.travel.agent.ai.graph.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 核心 Graph 分发给分支 Agent 的结构化任务。
 *
 * <p>系统架构位置：BranchDispatchNode -> <b>BranchTask</b> -> BranchExecuteNode</p>
 *
 * <p>职责：
 * <ul>
 *   <li>承载分支任务类型、查询文本、目的地、时间和约束条件。</li>
 *   <li>让 Graph 节点只传递稳定协议，不直接绑定某个具体工具方法。</li>
 *   <li>为后续并行执行、任务重试和任务追踪预留 taskId。</li>
 * </ul>
 * </p>
 */
public class BranchTask {

    /** 当前分支任务的唯一标识，用于和 BranchResult 对齐。 */
    private String taskId;

    /** 分支任务类型，决定 BranchAgentFacade 调用哪类能力。 */
    private BranchTaskType type;

    /** 面向分支 Agent / 工具的查询文本。 */
    private String query;

    /** 当前任务涉及的目的地列表。 */
    private List<String> destinations = new ArrayList<>();

    /** 当前任务涉及的出行时间。 */
    private String travelTime;

    /** 用户偏好、预算、避开人流等补充约束。 */
    private List<String> constraints = new ArrayList<>();

    /**
     * Jackson / 测试场景需要的无参构造器。
     */
    public BranchTask() {
    }

    public BranchTask(String taskId,
                      BranchTaskType type,
                      String query,
                      List<String> destinations,
                      String travelTime,
                      List<String> constraints) {
        this.taskId = taskId;
        this.type = type;
        this.query = query;
        setDestinations(destinations);
        this.travelTime = travelTime;
        setConstraints(constraints);
    }

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public BranchTaskType getType() {
        return type;
    }

    public void setType(BranchTaskType type) {
        this.type = type;
    }

    public String getQuery() {
        return query;
    }

    public void setQuery(String query) {
        this.query = query;
    }

    public List<String> getDestinations() {
        return destinations;
    }

    public void setDestinations(List<String> destinations) {
        this.destinations = destinations == null ? new ArrayList<>() : destinations;
    }

    public String getTravelTime() {
        return travelTime;
    }

    public void setTravelTime(String travelTime) {
        this.travelTime = travelTime;
    }

    public List<String> getConstraints() {
        return constraints;
    }

    public void setConstraints(List<String> constraints) {
        this.constraints = constraints == null ? new ArrayList<>() : constraints;
    }
}
