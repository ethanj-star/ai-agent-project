package com.travel.agent.core.dto;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * MediaCrawler 采集任务 DTO（Core 层 - 小红书采集任务快照）。
 *
 * <p>系统架构位置：KnowledgeController -> CrawlTaskService -> <b>CrawlTaskDTO</b> -> MySQL crawl_tasks</p>
 *
 * <p>职责：
 * <ul>
 *   <li>保存一次基于 POI 主数据生成的小红书关键词任务。</li>
 *   <li>承载后续写入 MediaCrawler base_config.py 的配置预览。</li>
 *   <li>为后续采集执行、结果导入、清洗和 Pinecone upsert 串联任务 ID。</li>
 * </ul>
 * </p>
 */
public class CrawlTaskDTO {

    /** 任务 ID，例如 crawl-uuid。 */
    private String taskId;

    /** 当前任务生命周期状态。 */
    private CrawlTaskStatus status = CrawlTaskStatus.DRAFT;

    /** 本次任务覆盖的国家代码。 */
    private List<String> countryCodes = new ArrayList<>();

    /** 本次任务使用的采集风格标签。 */
    private List<String> styleTags = new ArrayList<>();

    /** 关键词列表。 */
    private List<CrawlTaskKeywordDTO> keywords = new ArrayList<>();

    /** MediaCrawler base_config.py 目标路径；第一版只用于提示和预览。 */
    private String baseConfigPath;

    /** 可人工审查的 KEYWORDS 配置片段。 */
    private String configPreview;

    /** 任务错误信息；非失败状态通常为空。 */
    private String errorMessage;

    /** 创建时间。 */
    private LocalDateTime createdAt;

    /** 更新时间。 */
    private LocalDateTime updatedAt;

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = cleanText(taskId);
    }

    public CrawlTaskStatus getStatus() {
        return status;
    }

    public void setStatus(CrawlTaskStatus status) {
        this.status = status == null ? CrawlTaskStatus.DRAFT : status;
    }

    public List<String> getCountryCodes() {
        return countryCodes;
    }

    public void setCountryCodes(List<String> countryCodes) {
        this.countryCodes = cleanList(countryCodes);
    }

    public List<String> getStyleTags() {
        return styleTags;
    }

    public void setStyleTags(List<String> styleTags) {
        this.styleTags = cleanList(styleTags);
    }

    public List<CrawlTaskKeywordDTO> getKeywords() {
        return keywords;
    }

    public void setKeywords(List<CrawlTaskKeywordDTO> keywords) {
        this.keywords = keywords == null ? new ArrayList<>() : new ArrayList<>(keywords);
    }

    public String getBaseConfigPath() {
        return baseConfigPath;
    }

    public void setBaseConfigPath(String baseConfigPath) {
        this.baseConfigPath = cleanText(baseConfigPath);
    }

    public String getConfigPreview() {
        return configPreview;
    }

    public void setConfigPreview(String configPreview) {
        this.configPreview = cleanText(configPreview);
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = cleanText(errorMessage);
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public int keywordCount() {
        return keywords == null ? 0 : keywords.size();
    }

    private static List<String> cleanList(List<String> values) {
        List<String> cleaned = new ArrayList<>();
        if (values == null) {
            return cleaned;
        }
        for (String value : values) {
            String clean = cleanText(value);
            if (clean != null) {
                cleaned.add(clean);
            }
        }
        return cleaned;
    }

    private static String cleanText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
