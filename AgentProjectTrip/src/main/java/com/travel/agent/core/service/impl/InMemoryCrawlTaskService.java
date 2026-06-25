package com.travel.agent.core.service.impl;

import com.travel.agent.core.dto.CrawlTaskDTO;
import com.travel.agent.core.dto.CrawlTaskKeywordDTO;
import com.travel.agent.core.dto.CrawlTaskStatus;
import com.travel.agent.core.dto.TravelPoiDTO;
import com.travel.agent.core.etl.MediacrawlerConfigWriter;
import com.travel.agent.core.etl.MediacrawlerKeywordBuilder;
import com.travel.agent.core.service.CrawlTaskService;
import com.travel.agent.core.service.PoiCatalogService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 内存版 MediaCrawler 采集任务服务。
 *
 * <p>系统架构位置：KnowledgeController -> CrawlTaskService -> <b>InMemoryCrawlTaskService</b></p>
 *
 * <p>职责：在未启用 JDBC 任务服务时提供开发兜底。任务只保存在当前 JVM 内存中，
 * 重启后会丢失，适合本地调试关键词生成和配置预览。</p>
 */
@Service
@ConditionalOnMissingBean(CrawlTaskService.class)
public class InMemoryCrawlTaskService implements CrawlTaskService {

    /** 当前 JVM 内的任务快照。 */
    private final Map<String, CrawlTaskDTO> tasks = new LinkedHashMap<>();

    private final PoiCatalogService poiCatalogService;
    private final MediacrawlerKeywordBuilder keywordBuilder;
    private final MediacrawlerConfigWriter configWriter;

    public InMemoryCrawlTaskService(PoiCatalogService poiCatalogService,
                                    MediacrawlerKeywordBuilder keywordBuilder,
                                    MediacrawlerConfigWriter configWriter) {
        this.poiCatalogService = poiCatalogService;
        this.keywordBuilder = keywordBuilder;
        this.configWriter = configWriter;
    }

    @Override
    public synchronized CrawlTaskDTO createKeywordTask(List<String> countryCodes,
                                                       List<String> styleTags,
                                                       int limit,
                                                       String baseConfigPath) {
        List<TravelPoiDTO> pois = poiCatalogService.listEnabledPois(countryCodes);
        List<String> keywords = keywordBuilder.buildKeywords(pois, styleTags, limit);

        CrawlTaskDTO task = new CrawlTaskDTO();
        task.setTaskId("crawl-" + UUID.randomUUID());
        task.setStatus(CrawlTaskStatus.READY_FOR_CONFIG);
        task.setCountryCodes(countryCodes);
        task.setStyleTags(styleTags);
        task.setKeywords(toKeywordDtos(keywords));
        task.setBaseConfigPath(resolveBaseConfigPath(baseConfigPath));
        task.setConfigPreview(configWriter.renderKeywordsConfig(keywords));
        task.setCreatedAt(LocalDateTime.now());
        task.setUpdatedAt(task.getCreatedAt());
        tasks.put(task.getTaskId(), task);
        return task;
    }

    @Override
    public synchronized Optional<CrawlTaskDTO> findTask(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(tasks.get(taskId.trim()));
    }

    private static List<CrawlTaskKeywordDTO> toKeywordDtos(List<String> keywords) {
        List<CrawlTaskKeywordDTO> result = new ArrayList<>();
        if (keywords == null) {
            return result;
        }
        int order = 1;
        for (String keyword : keywords) {
            CrawlTaskKeywordDTO dto = new CrawlTaskKeywordDTO();
            dto.setOrder(order++);
            dto.setKeyword(keyword);
            dto.setSelected(true);
            result.add(dto);
        }
        return result;
    }

    private static String resolveBaseConfigPath(String baseConfigPath) {
        return baseConfigPath == null || baseConfigPath.isBlank()
                ? MediacrawlerConfigWriter.DEFAULT_BASE_CONFIG_PATH
                : baseConfigPath.trim();
    }
}
