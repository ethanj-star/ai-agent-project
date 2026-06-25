package com.travel.agent.core.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * 旅行景点主数据 DTO（Core 层 - POI Catalog）。
 *
 * <p>系统架构位置：KnowledgeController -> PoiCatalogService -> <b>TravelPoiDTO</b> -> MediaCrawlerKeywordBuilder</p>
 *
 * <p>职责：
 * <ul>
 *   <li>描述一个国家 / 城市下可被采集和检索的旅行 POI。</li>
 *   <li>保存风格标签、热度和 RAG 覆盖状态，供后续发现知识库缺口。</li>
 *   <li>作为 MediaCrawler 关键词生成的输入，不直接代表实时事实。</li>
 * </ul>
 * </p>
 */
public class TravelPoiDTO {

    /** 稳定 POI ID，例如 poi-fr-paris-louvre。 */
    private String poiId;

    /** 国家代码，例如 FR / IT / CH。 */
    private String countryCode;

    /** 城市或地区名称，例如 Paris / Rome / Jungfrau Region。 */
    private String cityName;

    /** 英文 POI 名称，便于未来对接官方资料和英文搜索。 */
    private String poiName;

    /** 中文或本地展示名，便于生成中文小红书关键词。 */
    private String localName;

    /** 标签，例如 museum、ticket、crowd、slow-travel。 */
    private List<String> tags = new ArrayList<>();

    /** 热度等级 1-5，第一版用于排序和采集优先级。 */
    private int popularityLevel = 3;

    /** 是否启用该 POI 的采集和 RAG 运营。 */
    private boolean enabled = true;

    /** RAG 覆盖状态，例如 MISSING / PARTIAL / READY。 */
    private String ragCoverageStatus;

    /** 运营备注，记录为什么需要补内容或有哪些风险。 */
    private String notes;

    public String getPoiId() {
        return poiId;
    }

    public void setPoiId(String poiId) {
        this.poiId = cleanText(poiId);
    }

    public String getCountryCode() {
        return countryCode;
    }

    public void setCountryCode(String countryCode) {
        this.countryCode = cleanText(countryCode);
    }

    public String getCityName() {
        return cityName;
    }

    public void setCityName(String cityName) {
        this.cityName = cleanText(cityName);
    }

    public String getPoiName() {
        return poiName;
    }

    public void setPoiName(String poiName) {
        this.poiName = cleanText(poiName);
    }

    public String getLocalName() {
        return localName;
    }

    public void setLocalName(String localName) {
        this.localName = cleanText(localName);
    }

    public List<String> getTags() {
        return tags;
    }

    public void setTags(List<String> tags) {
        this.tags = tags == null ? new ArrayList<>() : new ArrayList<>(tags);
    }

    public int getPopularityLevel() {
        return popularityLevel;
    }

    public void setPopularityLevel(int popularityLevel) {
        this.popularityLevel = Math.min(5, Math.max(1, popularityLevel));
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getRagCoverageStatus() {
        return ragCoverageStatus;
    }

    public void setRagCoverageStatus(String ragCoverageStatus) {
        this.ragCoverageStatus = cleanText(ragCoverageStatus);
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = cleanText(notes);
    }

    private static String cleanText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
