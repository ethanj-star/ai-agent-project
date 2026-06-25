package com.travel.agent.core.service;

import com.travel.agent.core.dto.CrawlTaskDTO;

import java.util.List;
import java.util.Optional;

/**
 * MediaCrawler 采集任务服务（Core 层 - RAG 知识运营任务编排）。
 *
 * <p>系统架构位置：KnowledgeController -> <b>CrawlTaskService</b> -> POI Catalog / KeywordBuilder / MySQL</p>
 *
 * <p>职责：
 * <ul>
 *   <li>根据国家、风格和 POI 主数据创建关键词采集任务。</li>
 *   <li>保存关键词列表和 MediaCrawler base_config.py 配置预览。</li>
 *   <li>为后续真实爬虫执行、结果导入和 Pinecone upsert 提供任务 ID。</li>
 * </ul>
 * </p>
 */
public interface CrawlTaskService {

    /**
     * 创建一个 MediaCrawler 关键词采集任务。
     *
     * @param countryCodes   国家代码；为空时使用所有启用 POI
     * @param styleTags      风格标签；为空时使用关键词生成器默认标签
     * @param limit          最多生成关键词数量
     * @param baseConfigPath 目标 base_config.py 路径；为空时使用默认路径
     * @return 已保存的采集任务
     */
    CrawlTaskDTO createKeywordTask(List<String> countryCodes,
                                   List<String> styleTags,
                                   int limit,
                                   String baseConfigPath);

    /**
     * 查询采集任务。
     *
     * @param taskId 任务 ID
     * @return 找到时返回任务快照
     */
    Optional<CrawlTaskDTO> findTask(String taskId);
}
