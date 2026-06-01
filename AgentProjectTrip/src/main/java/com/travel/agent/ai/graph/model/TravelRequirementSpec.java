package com.travel.agent.ai.graph.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 结构化旅行需求表（Graph 层 - 用户需求唯一真相源）。
 *
 * <p>系统架构位置：RequirementExtractionAgent -> <b>TravelRequirementSpec</b> -> LangGraphPlannerFacade</p>
 *
 * <p>职责：
 * <ul>
 *   <li>承载用户在生成完整行程前必须确认的目的地、时间、天数、预算、人数和偏好。</li>
 *   <li>把随意自然语言转换成稳定字段，减少 Planner 对原始文本的误读。</li>
 *   <li>作为第五阶段生成门控、第三阶段分支任务和第四阶段风险审查共同参考的结构化上下文。</li>
 * </ul>
 * </p>
 *
 * <p>空值策略：模型或前端没有提供的信息保持 {@code null}，不在 DTO 层脑补默认值；
 * 集合字段在 setter 中转为空列表，避免后续节点空指针。</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class TravelRequirementSpec {

    /** 需求表 ID，后续补全、确认、生成和修改都围绕它流转。 */
    private String requirementId;

    /** 会话 ID，用于把多轮自然语言补充合并到同一张需求表。 */
    private String sessionId;

    /** 用户最初输入的自然语言，保留用于审计和 Prompt 辅助上下文。 */
    private String originalMessage;

    /** 明确目的地列表，商业版不应长期停留在“欧洲”这种宽泛概念。 */
    private List<String> destinations = new ArrayList<>();

    /** 出发城市，影响航班、入境口岸、预算和签证假设。 */
    private String departureCity;

    /** 用户原始时间表达，例如“国庆”“下个月”“2026年10月1日”。 */
    private String startDateText;

    /** 能被解析成具体日期时写入 ISO 日期；模糊时间保持为空。 */
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate startDate;

    /** 行程总天数，例如 10 天写入 10。 */
    private Integer durationDays;

    /** 旅行人数，影响住宿、门票、餐饮和交通预算。 */
    private Integer travelerCount;

    /** 预算金额，币种由 budgetCurrency 单独表达。 */
    private BigDecimal budgetAmount;

    /** 预算币种，例如 CNY / EUR / USD / GBP。 */
    private String budgetCurrency;

    /** 预算是否包含国际机票；不确定时保持 null，避免预算误算。 */
    private Boolean budgetIncludesInternationalFlight;

    /** 正向偏好，例如“小众”“美食”“博物馆”“亲子”。 */
    private List<String> preferences = new ArrayList<>();

    /** 负向偏好，例如“避开人多”“不住青旅”“少去网红景点”。 */
    private List<String> avoidances = new ArrayList<>();

    /** 旅行风格，例如慢游、深度游、打卡、高舒适度。 */
    private String travelStyle;

    /** 住宿偏好，例如青旅、经济酒店、中档酒店、民宿。 */
    private String accommodationPreference;

    /** 交通偏好，例如火车、自驾、公共交通、少换乘。 */
    private String transportPreference;

    /** 当前需求表生命周期状态。 */
    private RequirementStatus status = RequirementStatus.DRAFT;

    /** 当前仍缺失的字段名，供前端高亮展示。 */
    private List<String> missingFields = new ArrayList<>();

    /** 非阻塞提示，例如预算偏低、时间模糊、路线过满等。 */
    private List<String> warnings = new ArrayList<>();

    public String getRequirementId() {
        return requirementId;
    }

    public void setRequirementId(String requirementId) {
        this.requirementId = requirementId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getOriginalMessage() {
        return originalMessage;
    }

    public void setOriginalMessage(String originalMessage) {
        this.originalMessage = originalMessage;
    }

    public List<String> getDestinations() {
        return destinations;
    }

    public void setDestinations(List<String> destinations) {
        this.destinations = cleanList(destinations);
    }

    public String getDepartureCity() {
        return departureCity;
    }

    public void setDepartureCity(String departureCity) {
        this.departureCity = departureCity;
    }

    public String getStartDateText() {
        return startDateText;
    }

    public void setStartDateText(String startDateText) {
        this.startDateText = startDateText;
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

    public Integer getTravelerCount() {
        return travelerCount;
    }

    public void setTravelerCount(Integer travelerCount) {
        this.travelerCount = travelerCount;
    }

    public BigDecimal getBudgetAmount() {
        return budgetAmount;
    }

    public void setBudgetAmount(BigDecimal budgetAmount) {
        this.budgetAmount = budgetAmount;
    }

    public String getBudgetCurrency() {
        return budgetCurrency;
    }

    public void setBudgetCurrency(String budgetCurrency) {
        this.budgetCurrency = budgetCurrency;
    }

    public Boolean getBudgetIncludesInternationalFlight() {
        return budgetIncludesInternationalFlight;
    }

    public void setBudgetIncludesInternationalFlight(Boolean budgetIncludesInternationalFlight) {
        this.budgetIncludesInternationalFlight = budgetIncludesInternationalFlight;
    }

    public List<String> getPreferences() {
        return preferences;
    }

    public void setPreferences(List<String> preferences) {
        this.preferences = cleanList(preferences);
    }

    public List<String> getAvoidances() {
        return avoidances;
    }

    public void setAvoidances(List<String> avoidances) {
        this.avoidances = cleanList(avoidances);
    }

    public String getTravelStyle() {
        return travelStyle;
    }

    public void setTravelStyle(String travelStyle) {
        this.travelStyle = travelStyle;
    }

    public String getAccommodationPreference() {
        return accommodationPreference;
    }

    public void setAccommodationPreference(String accommodationPreference) {
        this.accommodationPreference = accommodationPreference;
    }

    public String getTransportPreference() {
        return transportPreference;
    }

    public void setTransportPreference(String transportPreference) {
        this.transportPreference = transportPreference;
    }

    public RequirementStatus getStatus() {
        return status;
    }

    public void setStatus(RequirementStatus status) {
        this.status = status == null ? RequirementStatus.DRAFT : status;
    }

    public List<String> getMissingFields() {
        return missingFields;
    }

    public void setMissingFields(List<String> missingFields) {
        this.missingFields = cleanList(missingFields);
    }

    public List<String> getWarnings() {
        return warnings;
    }

    public void setWarnings(List<String> warnings) {
        this.warnings = cleanList(warnings);
    }

    private static List<String> cleanList(List<String> values) {
        List<String> cleaned = new ArrayList<>();
        if (values == null || values.isEmpty()) {
            return cleaned;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                cleaned.add(value.trim());
            }
        }
        return cleaned;
    }
}
