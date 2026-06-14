package com.travel.agent.ai.graph.model;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 核心 Graph 分发给分支 Agent 的结构化任务。
 *
 * <p>系统架构位置：BranchDispatchNode / BranchDispatchGuardNode -> <b>BranchTask</b> -> BranchExecuteNode</p>
 *
 * <p>职责：
 * <ul>
 *   <li>承载分支任务类型、查询文本、目的地、时间、日期、出发地和约束条件。</li>
 *   <li>让 Graph 节点只传递稳定协议，不直接绑定某个具体工具方法。</li>
 *   <li>为后续并行执行、任务重试、任务追踪和模型分支 Agent 预留 taskId。</li>
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

    /** 已解析出的出发日期；航班和酒店真实工具优先使用该字段。 */
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate startDate;

    /** 行程总天数；酒店分支用它推导退房日期。 */
    private Integer durationDays;

    /** 出发城市；航班分支用它解析出发机场代码。 */
    private String departureCity;

    /** 住宿偏好；酒店分支用它判断是否需要住宿价格参考。 */
    private String accommodationPreference;

    /** 预算是否包含国际机票；Planner 用它决定航班价格是否进入预算。 */
    private Boolean budgetIncludesInternationalFlight;

    /** 第十三阶段模型或规则派发该任务的原因，供日志、Trace 和后续调试使用。 */
    private String dispatchReason;

    /** 第十三阶段模型给出的任务优先级，第一版只作为 Guard 排序参考。 */
    private String dispatchPriority;

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

    public BranchTask(String taskId,
                      BranchTaskType type,
                      String query,
                      List<String> destinations,
                      String travelTime,
                      List<String> constraints,
                      LocalDate startDate,
                      Integer durationDays,
                      String departureCity,
                      String accommodationPreference,
                      Boolean budgetIncludesInternationalFlight) {
        this(taskId, type, query, destinations, travelTime, constraints);
        this.startDate = startDate;
        this.durationDays = durationDays;
        this.departureCity = cleanText(departureCity);
        this.accommodationPreference = cleanText(accommodationPreference);
        this.budgetIncludesInternationalFlight = budgetIncludesInternationalFlight;
    }

    public BranchTask(String taskId,
                      BranchTaskType type,
                      String query,
                      List<String> destinations,
                      String travelTime,
                      List<String> constraints,
                      LocalDate startDate,
                      Integer durationDays,
                      String departureCity,
                      String accommodationPreference,
                      Boolean budgetIncludesInternationalFlight,
                      String dispatchReason,
                      String dispatchPriority) {
        this(taskId,
                type,
                query,
                destinations,
                travelTime,
                constraints,
                startDate,
                durationDays,
                departureCity,
                accommodationPreference,
                budgetIncludesInternationalFlight);
        this.dispatchReason = cleanText(dispatchReason);
        this.dispatchPriority = cleanText(dispatchPriority);
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
        // 分支任务允许没有目的地，但不允许把 null 继续传给后续节点。
        this.destinations = destinations == null ? new ArrayList<>() : destinations;
    }

    public String getTravelTime() {
        return travelTime;
    }

    public void setTravelTime(String travelTime) {
        this.travelTime = travelTime;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public void setStartDate(LocalDate startDate) {
        this.startDate = startDate;
    }

    public Integer getDurationDays() {
        return durationDays;
    }

    public void setDurationDays(Integer durationDays) {
        this.durationDays = durationDays;
    }

    public String getDepartureCity() {
        return departureCity;
    }

    public void setDepartureCity(String departureCity) {
        this.departureCity = cleanText(departureCity);
    }

    public String getAccommodationPreference() {
        return accommodationPreference;
    }

    public void setAccommodationPreference(String accommodationPreference) {
        this.accommodationPreference = cleanText(accommodationPreference);
    }

    public Boolean getBudgetIncludesInternationalFlight() {
        return budgetIncludesInternationalFlight;
    }

    public void setBudgetIncludesInternationalFlight(Boolean budgetIncludesInternationalFlight) {
        this.budgetIncludesInternationalFlight = budgetIncludesInternationalFlight;
    }

    public String getDispatchReason() {
        return dispatchReason;
    }

    public void setDispatchReason(String dispatchReason) {
        this.dispatchReason = cleanText(dispatchReason);
    }

    public String getDispatchPriority() {
        return dispatchPriority;
    }

    public void setDispatchPriority(String dispatchPriority) {
        this.dispatchPriority = cleanText(dispatchPriority);
    }

    public List<String> getConstraints() {
        return constraints;
    }

    public void setConstraints(List<String> constraints) {
        // 约束为空时代表“没有额外限制”，用空列表比 null 更适合遍历和拼 prompt。
        this.constraints = constraints == null ? new ArrayList<>() : constraints;
    }

    private static String cleanText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
