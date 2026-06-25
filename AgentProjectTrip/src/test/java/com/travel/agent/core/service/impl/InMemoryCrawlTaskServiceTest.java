package com.travel.agent.core.service.impl;

import com.travel.agent.core.dto.CrawlTaskDTO;
import com.travel.agent.core.dto.CrawlTaskStatus;
import com.travel.agent.core.etl.MediacrawlerConfigWriter;
import com.travel.agent.core.etl.MediacrawlerKeywordBuilder;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * InMemoryCrawlTaskService 的单元测试。
 *
 * <p>重点验证第 14 阶段“POI -> 关键词 -> 任务 -> 配置预览”的最小闭环。</p>
 */
class InMemoryCrawlTaskServiceTest {

    @Test
    void createKeywordTaskStoresTaskAndConfigPreview() {
        InMemoryCrawlTaskService service = new InMemoryCrawlTaskService(
                new InMemoryPoiCatalogService(),
                new MediacrawlerKeywordBuilder(),
                new MediacrawlerConfigWriter());

        CrawlTaskDTO task = service.createKeywordTask(List.of("FR"), List.of("小众", "避坑"), 10, null);

        assertThat(task.getTaskId()).startsWith("crawl-");
        assertThat(task.getStatus()).isEqualTo(CrawlTaskStatus.READY_FOR_CONFIG);
        assertThat(task.getKeywords()).isNotEmpty();
        assertThat(task.getConfigPreview()).contains("KEYWORDS = \"");
        assertThat(service.findTask(task.getTaskId())).contains(task);
    }
}
