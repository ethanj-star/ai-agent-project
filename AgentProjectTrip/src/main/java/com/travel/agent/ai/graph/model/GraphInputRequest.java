package com.travel.agent.ai.graph.model;

import com.travel.agent.ai.dto.GatekeeperResponse;

/**
 * LangGraph 规划黑箱的入口请求对象（Graph 层 - 入参协议）。
 *
 * <p>系统架构位置：MastermindAgent → <b>GraphInputRequest</b> → LangGraphPlannerFacade</p>
 *
 * <p>职责：
 * <ul>
 *   <li>承载用户原始自然语言输入，保证 Planner 节点仍能看到完整上下文。</li>
 *   <li>承载 Gatekeeper 已解析出的结构化路由结果，避免后续节点重复做意图识别。</li>
 *   <li>第五阶段开始可携带已确认的结构化旅行需求表，作为 Planner 的优先事实来源。</li>
 *   <li>作为 Spring AI 外围层和 Graph 黑箱层之间的稳定边界协议。</li>
 * </ul>
 * </p>
 */
public class GraphInputRequest {

    /** 用户原始输入，保留完整自然语言上下文供 Planner 和 RAG 检索使用 */
    private String userQuery;

    /** Gatekeeper 的结构化路由结果，包含 intent、locations、time、keywords 等信息 */
    private GatekeeperResponse route;

    /** 当前会话 ID，用于第二阶段查找 pending 状态并续跑原任务。 */
    private String sessionId;

    /** 当前请求是否来自用户对上一轮追问的补充回答。 */
    private boolean resumeMode;

    /** 第五阶段确认后的结构化旅行需求表；为空时保持旧自然语言入口行为。 */
    private TravelRequirementSpec requirementSpec;

    /**
     * Jackson / 测试场景需要的无参构造器。
     */
    public GraphInputRequest() {
    }

    /**
     * 便捷构造器，用于 MastermindAgent 将当前用户消息和路由结果打包传入 Graph 黑箱。
     *
     * @param userQuery 用户原始自然语言输入
     * @param route     Gatekeeper 输出的结构化路由结果
     */
    public GraphInputRequest(String userQuery, GatekeeperResponse route) {
        this.userQuery = userQuery;
        this.route = route;
    }

    /**
     * 携带会话 ID 的便捷构造器，用于第二阶段澄清循环。
     *
     * @param userQuery 用户原始自然语言输入
     * @param route     Gatekeeper 输出的结构化路由结果
     * @param sessionId 当前会话 ID
     */
    public GraphInputRequest(String userQuery, GatekeeperResponse route, String sessionId) {
        this.userQuery = userQuery;
        this.route = route;
        this.sessionId = sessionId;
    }

    public String getUserQuery() {
        return userQuery;
    }

    public void setUserQuery(String userQuery) {
        this.userQuery = userQuery;
    }

    public GatekeeperResponse getRoute() {
        return route;
    }

    public void setRoute(GatekeeperResponse route) {
        this.route = route;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public boolean isResumeMode() {
        return resumeMode;
    }

    public void setResumeMode(boolean resumeMode) {
        this.resumeMode = resumeMode;
    }

    public TravelRequirementSpec getRequirementSpec() {
        return requirementSpec;
    }

    public void setRequirementSpec(TravelRequirementSpec requirementSpec) {
        this.requirementSpec = requirementSpec;
    }
}
