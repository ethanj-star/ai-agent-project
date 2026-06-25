package com.travel.agent.core.dto;

/**
 * MediaCrawler 采集任务关键词 DTO。
 *
 * <p>系统架构位置：CrawlTaskService -> <b>CrawlTaskKeywordDTO</b> -> crawl_task_keywords</p>
 *
 * <p>职责：记录某次采集任务下的一条小红书搜索关键词，以及它在配置中的顺序。
 * 第 14 阶段先默认全部 selected，后续可以加前端审核勾选。</p>
 */
public class CrawlTaskKeywordDTO {

    /** 关键词在当前任务中的顺序，从 1 开始。 */
    private int order;

    /** 小红书搜索关键词文本。 */
    private String keyword;

    /** 是否选中写入 MediaCrawler KEYWORDS；第一版默认 true。 */
    private boolean selected = true;

    public int getOrder() {
        return order;
    }

    public void setOrder(int order) {
        this.order = Math.max(1, order);
    }

    public String getKeyword() {
        return keyword;
    }

    public void setKeyword(String keyword) {
        this.keyword = keyword == null || keyword.isBlank() ? null : keyword.trim();
    }

    public boolean isSelected() {
        return selected;
    }

    public void setSelected(boolean selected) {
        this.selected = selected;
    }
}
