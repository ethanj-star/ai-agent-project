package com.travel.agent.core.dto;

/**
 * MediaCrawler 采集任务状态（Core 层 - RAG 知识运营任务生命周期）。
 *
 * <p>系统架构位置：KnowledgeController -> CrawlTaskService -> <b>CrawlTaskStatus</b> -> MySQL crawl_tasks</p>
 *
 * <p>职责：描述一个小红书采集任务从关键词候选到后续执行、导入和入库的生命周期。
 * 第 14 阶段第一版只创建 DRAFT / READY_FOR_CONFIG，不自动运行爬虫。</p>
 */
public enum CrawlTaskStatus {

    /** 任务已创建，关键词仍处于候选或待审核状态。 */
    DRAFT,

    /** 关键词已生成配置预览，可以人工复制或后续写入 base_config.py。 */
    READY_FOR_CONFIG,

    /** 预留状态：后续 Java 后端真正触发 MediaCrawler 时使用。 */
    RUNNING,

    /** 预留状态：采集完成但尚未导入清洗。 */
    CRAWLED,

    /** 预留状态：采集结果已经清洗并写入 Pinecone。 */
    INGESTED,

    /** 任务失败，错误信息写入 crawl_tasks.error_message。 */
    FAILED
}
