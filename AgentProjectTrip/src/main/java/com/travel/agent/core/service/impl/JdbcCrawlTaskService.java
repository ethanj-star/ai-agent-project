package com.travel.agent.core.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.agent.core.dto.CrawlTaskDTO;
import com.travel.agent.core.dto.CrawlTaskKeywordDTO;
import com.travel.agent.core.dto.CrawlTaskStatus;
import com.travel.agent.core.dto.TravelPoiDTO;
import com.travel.agent.core.etl.MediacrawlerConfigWriter;
import com.travel.agent.core.etl.MediacrawlerKeywordBuilder;
import com.travel.agent.core.service.CrawlTaskService;
import com.travel.agent.core.service.PoiCatalogService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * JDBC 版 MediaCrawler 采集任务服务。
 *
 * <p>系统架构位置：KnowledgeController -> CrawlTaskService -> <b>JdbcCrawlTaskService</b> -> MySQL crawl_tasks</p>
 *
 * <p>职责：
 * <ul>
 *   <li>基于 POI 主数据生成小红书关键词任务。</li>
 *   <li>把任务和关键词持久化到 MySQL，便于后续人工审核、执行爬虫和导入数据。</li>
 *   <li>生成 base_config.py 的 KEYWORDS 配置预览，但不直接写外部文件。</li>
 * </ul>
 * </p>
 */
@Service
@ConditionalOnProperty(name = "travel.persistence.type", havingValue = "jdbc")
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class JdbcCrawlTaskService implements CrawlTaskService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final PoiCatalogService poiCatalogService;
    private final MediacrawlerKeywordBuilder keywordBuilder;
    private final MediacrawlerConfigWriter configWriter;

    public JdbcCrawlTaskService(JdbcTemplate jdbcTemplate,
                                ObjectMapper objectMapper,
                                PoiCatalogService poiCatalogService,
                                MediacrawlerKeywordBuilder keywordBuilder,
                                MediacrawlerConfigWriter configWriter) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.poiCatalogService = poiCatalogService;
        this.keywordBuilder = keywordBuilder;
        this.configWriter = configWriter;
    }

    @Override
    @Transactional
    public CrawlTaskDTO createKeywordTask(List<String> countryCodes,
                                          List<String> styleTags,
                                          int limit,
                                          String baseConfigPath) {
        List<TravelPoiDTO> pois = poiCatalogService.listEnabledPois(countryCodes);
        List<String> keywords = keywordBuilder.buildKeywords(pois, styleTags, limit);
        String taskId = "crawl-" + UUID.randomUUID();
        String resolvedPath = resolveBaseConfigPath(baseConfigPath);
        String configPreview = configWriter.renderKeywordsConfig(keywords);

        jdbcTemplate.update("""
                        INSERT INTO crawl_tasks
                        (task_id, status, country_codes_json, style_tags_json, keyword_count,
                         base_config_path, config_preview)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """,
                taskId,
                CrawlTaskStatus.READY_FOR_CONFIG.name(),
                writeJson(cleanList(countryCodes)),
                writeJson(cleanList(styleTags)),
                keywords.size(),
                resolvedPath,
                configPreview);

        int order = 1;
        for (String keyword : keywords) {
            jdbcTemplate.update("""
                            INSERT INTO crawl_task_keywords
                            (task_id, keyword_order, keyword_text, selected)
                            VALUES (?, ?, ?, TRUE)
                            """,
                    taskId,
                    order++,
                    keyword);
        }

        return findTask(taskId).orElseGet(() -> buildFallbackTask(taskId, countryCodes, styleTags, keywords, resolvedPath, configPreview));
    }

    @Override
    public Optional<CrawlTaskDTO> findTask(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return Optional.empty();
        }
        try {
            CrawlTaskDTO task = jdbcTemplate.queryForObject("""
                            SELECT task_id, status, country_codes_json, style_tags_json,
                                   base_config_path, config_preview, error_message, created_at, updated_at
                            FROM crawl_tasks
                            WHERE task_id = ?
                            """,
                    (rs, rowNum) -> mapTask(rs),
                    taskId.trim());
            task.setKeywords(listKeywords(taskId.trim()));
            return Optional.of(task);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    private List<CrawlTaskKeywordDTO> listKeywords(String taskId) {
        return jdbcTemplate.query("""
                        SELECT keyword_order, keyword_text, selected
                        FROM crawl_task_keywords
                        WHERE task_id = ?
                        ORDER BY keyword_order
                        """,
                (rs, rowNum) -> mapKeyword(rs),
                taskId);
    }

    private CrawlTaskDTO mapTask(ResultSet rs) throws SQLException {
        CrawlTaskDTO task = new CrawlTaskDTO();
        task.setTaskId(rs.getString("task_id"));
        task.setStatus(parseStatus(rs.getString("status")));
        task.setCountryCodes(readStringList(rs.getString("country_codes_json")));
        task.setStyleTags(readStringList(rs.getString("style_tags_json")));
        task.setBaseConfigPath(rs.getString("base_config_path"));
        task.setConfigPreview(rs.getString("config_preview"));
        task.setErrorMessage(rs.getString("error_message"));
        task.setCreatedAt(toLocalDateTime(rs.getTimestamp("created_at")));
        task.setUpdatedAt(toLocalDateTime(rs.getTimestamp("updated_at")));
        return task;
    }

    private static CrawlTaskKeywordDTO mapKeyword(ResultSet rs) throws SQLException {
        CrawlTaskKeywordDTO keyword = new CrawlTaskKeywordDTO();
        keyword.setOrder(rs.getInt("keyword_order"));
        keyword.setKeyword(rs.getString("keyword_text"));
        keyword.setSelected(rs.getBoolean("selected"));
        return keyword;
    }

    private CrawlTaskDTO buildFallbackTask(String taskId,
                                           List<String> countryCodes,
                                           List<String> styleTags,
                                           List<String> keywords,
                                           String baseConfigPath,
                                           String configPreview) {
        CrawlTaskDTO task = new CrawlTaskDTO();
        task.setTaskId(taskId);
        task.setStatus(CrawlTaskStatus.READY_FOR_CONFIG);
        task.setCountryCodes(countryCodes);
        task.setStyleTags(styleTags);
        task.setKeywords(toKeywordDtos(keywords));
        task.setBaseConfigPath(baseConfigPath);
        task.setConfigPreview(configPreview);
        return task;
    }

    private static List<CrawlTaskKeywordDTO> toKeywordDtos(List<String> keywords) {
        List<CrawlTaskKeywordDTO> result = new ArrayList<>();
        int order = 1;
        if (keywords == null) {
            return result;
        }
        for (String keyword : keywords) {
            CrawlTaskKeywordDTO dto = new CrawlTaskKeywordDTO();
            dto.setOrder(order++);
            dto.setKeyword(keyword);
            dto.setSelected(true);
            result.add(dto);
        }
        return result;
    }

    private String writeJson(List<String> values) {
        try {
            return objectMapper.writeValueAsString(cleanList(values));
        } catch (Exception e) {
            return "[]";
        }
    }

    private List<String> readStringList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }

    private static CrawlTaskStatus parseStatus(String status) {
        try {
            return status == null ? CrawlTaskStatus.DRAFT : CrawlTaskStatus.valueOf(status);
        } catch (IllegalArgumentException e) {
            return CrawlTaskStatus.DRAFT;
        }
    }

    private static List<String> cleanList(List<String> values) {
        List<String> cleaned = new ArrayList<>();
        if (values == null) {
            return cleaned;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                cleaned.add(value.trim());
            }
        }
        return cleaned;
    }

    private static String resolveBaseConfigPath(String baseConfigPath) {
        return baseConfigPath == null || baseConfigPath.isBlank()
                ? MediacrawlerConfigWriter.DEFAULT_BASE_CONFIG_PATH
                : baseConfigPath.trim();
    }

    private static LocalDateTime toLocalDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }
}
