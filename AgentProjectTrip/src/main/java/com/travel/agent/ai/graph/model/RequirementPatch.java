package com.travel.agent.ai.graph.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 结构化需求表补丁。
 *
 * <p>系统架构位置：PlanModificationAgent -> <b>RequirementPatch</b> -> RequirementPatchNode</p>
 *
 * <p>职责：
 * <ul>
 *   <li>表达用户在自然语言修改中对核心旅行需求表造成的字段变更。</li>
 *   <li>避免把“预算改成900欧”“加瑞士”等核心需求变化误当作普通局部行程重写。</li>
 *   <li>作为第六阶段回到第五阶段确认流程的结构化桥梁。</li>
 * </ul>
 * </p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class RequirementPatch {

    /** 替换后的目的地列表；为空表示不改目的地。 */
    private List<String> destinations = new ArrayList<>();

    /** 新出发城市。 */
    private String departureCity;

    /** 新出行时间文本。 */
    private String startDateText;

    /** 新行程天数。 */
    private Integer durationDays;

    /** 新旅行人数。 */
    private Integer travelerCount;

    /** 新预算金额。 */
    private BigDecimal budgetAmount;

    /** 新预算币种。 */
    private String budgetCurrency;

    /** 新国际机票预算边界。 */
    private Boolean budgetIncludesInternationalFlight;

    /** 需要追加的正向偏好。 */
    private List<String> addPreferences = new ArrayList<>();

    /** 需要移除的正向偏好。 */
    private List<String> removePreferences = new ArrayList<>();

    /** 需要追加的负向偏好。 */
    private List<String> addAvoidances = new ArrayList<>();

    /** 需要移除的负向偏好。 */
    private List<String> removeAvoidances = new ArrayList<>();

    /** 新住宿偏好。 */
    private String accommodationPreference;

    /** 新交通偏好。 */
    private String transportPreference;

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

    public List<String> getAddPreferences() {
        return addPreferences;
    }

    public void setAddPreferences(List<String> addPreferences) {
        this.addPreferences = cleanList(addPreferences);
    }

    public List<String> getRemovePreferences() {
        return removePreferences;
    }

    public void setRemovePreferences(List<String> removePreferences) {
        this.removePreferences = cleanList(removePreferences);
    }

    public List<String> getAddAvoidances() {
        return addAvoidances;
    }

    public void setAddAvoidances(List<String> addAvoidances) {
        this.addAvoidances = cleanList(addAvoidances);
    }

    public List<String> getRemoveAvoidances() {
        return removeAvoidances;
    }

    public void setRemoveAvoidances(List<String> removeAvoidances) {
        this.removeAvoidances = cleanList(removeAvoidances);
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
