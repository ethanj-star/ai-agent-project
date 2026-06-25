package com.travel.agent.core.dto;

/**
 * 旅行国家主数据 DTO（Core 层 - RAG 知识运营基础数据）。
 *
 * <p>系统架构位置：KnowledgeController -> PoiCatalogService -> <b>TravelCountryDTO</b> -> MySQL travel_countries</p>
 *
 * <p>职责：承载第 14 阶段 RAG 知识库运营的国家维度。MediaCrawler 关键词生成、
 * POI 覆盖率统计和后续 Pinecone metadata filter 都会基于国家代码分组。</p>
 */
public class TravelCountryDTO {

    /** 国家代码，例如 FR / IT / CH。 */
    private String countryCode;

    /** 英文国家名，例如 France。 */
    private String countryName;

    /** 中文或本地展示名，例如 法国。 */
    private String localName;

    /** 是否启用该国家的 RAG 采集和检索运营。 */
    private boolean enabled;

    public String getCountryCode() {
        return countryCode;
    }

    public void setCountryCode(String countryCode) {
        this.countryCode = cleanText(countryCode);
    }

    public String getCountryName() {
        return countryName;
    }

    public void setCountryName(String countryName) {
        this.countryName = cleanText(countryName);
    }

    public String getLocalName() {
        return localName;
    }

    public void setLocalName(String localName) {
        this.localName = cleanText(localName);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    private static String cleanText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
