package com.travel.agent.web;

import com.travel.agent.ai.graph.model.RagRetrievalResult;
import com.travel.agent.ai.graph.model.TravelPlanState;
import com.travel.agent.ai.graph.model.TravelRequirementSpec;
import com.travel.agent.ai.rag.AdaptiveRagService;
import com.travel.agent.core.dto.TravelPoiDTO;
import com.travel.agent.core.etl.MediacrawlerKeywordBuilder;
import com.travel.agent.core.service.CrawlTaskService;
import com.travel.agent.core.service.KnowledgeBaseService;
import com.travel.agent.core.service.PoiCatalogService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 知识库（向量 / RAG）REST API 控制器（Web 层）
 *
 * <p>系统架构位置：<b>Web 层</b> → KnowledgeBaseService / AdaptiveRagService → VectorStore（Pinecone）
 *
 * <p>提供知识库数据灌入和 Adaptive RAG 调试接口，供开发阶段通过浏览器或 curl 手动触发。
 * 生产环境建议将写操作改为 {@code @PostMapping} 并加鉴权。
 *
 * <p>接口清单：
 * <ul>
 *   <li>{@code GET /api/v1/knowledge/ingest?fileName=france_italy_entities.jsonl}
 *       — 将提取好的法意瑞实体 JSONL 向量化灌入 Pinecone</li>
 *   <li>{@code POST /api/v1/knowledge/adaptive-rag/preview}
 *       — 预览 Adaptive RAG 的查询类型、检索策略和命中结果</li>
 *   <li>{@code GET /api/v1/knowledge/pois}
 *       — 查看当前 RAG 运营维护的 POI 主数据</li>
 *   <li>{@code POST /api/v1/knowledge/mediacrawler/keywords/preview}
 *       — 根据 POI 和风格标签生成 MediaCrawler 关键词候选</li>
 *   <li>{@code POST /api/v1/knowledge/mediacrawler/tasks}
 *       — 创建可追踪的 MediaCrawler 关键词采集任务</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/knowledge")
public class KnowledgeController {

    private final KnowledgeBaseService knowledgeBaseService;
    private final AdaptiveRagService adaptiveRagService;
    private final PoiCatalogService poiCatalogService;
    private final MediacrawlerKeywordBuilder keywordBuilder;
    private final CrawlTaskService crawlTaskService;

    public KnowledgeController(KnowledgeBaseService knowledgeBaseService,
                               AdaptiveRagService adaptiveRagService,
                               PoiCatalogService poiCatalogService,
                               MediacrawlerKeywordBuilder keywordBuilder,
                               CrawlTaskService crawlTaskService) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.adaptiveRagService = adaptiveRagService;
        this.poiCatalogService = poiCatalogService;
        this.keywordBuilder = keywordBuilder;
        this.crawlTaskService = crawlTaskService;
    }

    /**
     * 触发真实旅游实体数据的向量化灌入。
     *
     * <p>调用链：
     * <ol>
     *   <li>读取 {@code data/extracted/} 目录下的指定 JSONL 文件。</li>
     *   <li>逐行解析为 Spring AI {@code Document}（语义文本 + Metadata）。</li>
     *   <li>每 20 条调用一次 {@code vectorStore.add()}，Embedding 后落入 Pinecone。</li>
     * </ol>
     *
     * <p>⚡ 注意：此接口为同步阻塞调用。文件较大时（数百条实体）请求会阻塞较长时间，
     * 属正常现象——每批次需等待 Embedding API 往返延迟。请耐心等待返回。
     *
     * @param fileName 文件名，默认值为 {@code france_italy_entities.jsonl}，
     *                 对应 {@code data/extracted/france_italy_entities.jsonl}
     * @return {@code 200 OK} 成功提示；{@code 400 Bad Request} 参数为空时
     */
    @GetMapping("/ingest")
    public ResponseEntity<String> ingest(
            @RequestParam(defaultValue = "france_italy_entities.jsonl") String fileName) {

        // fileName 为空时无法定位 data/extracted 下的文件，提前返回 400。
        if (fileName.isBlank()) {
            return ResponseEntity.badRequest()
                    .body("ERROR: 'fileName' 参数不能为空。");
        }

        // 具体的文件读取、Document 构造、Embedding 和向量库写入都封装在 Service 层。
        knowledgeBaseService.ingestRealKnowledge(fileName);

        return ResponseEntity.ok(
                "真实知识库数据已成功分批灌入 Pinecone！文件：" + fileName);
    }

    /**
     * 预览 Adaptive RAG 对某个旅行问题的检索决策。
     *
     * <p>HTTP 语义：
     * <ul>
     *   <li>{@code 200 OK}：分类、策略选择和检索执行成功。</li>
     *   <li>{@code 400 Bad Request}：请求体为空或 message 为空。</li>
     *   <li>{@code 503 Service Unavailable}：底层向量库或检索服务不可用。</li>
     * </ul>
     *
     * <p>这个接口主要用于第 14 阶段人工测试，不会创建正式规划任务，也不会扣额度。</p>
     *
     * @param request 调试请求，包含用户问题和可选结构化需求字段
     * @return Adaptive RAG 决策和检索结果
     */
    @PostMapping("/adaptive-rag/preview")
    public ResponseEntity<?> previewAdaptiveRag(@RequestBody AdaptiveRagPreviewRequest request) {
        if (request == null || !hasText(request.message())) {
            return ResponseEntity.badRequest().body("ERROR: 'message' 不能为空。");
        }

        try {
            RagRetrievalResult result = adaptiveRagService.retrieve(toState(request));
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            // 调试接口直接返回可读错误，方便开发阶段判断是 Pinecone、Embedding 还是配置问题。
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body("Adaptive RAG preview failed: " + e.getMessage());
        }
    }

    /**
     * 查看当前启用的 RAG 国家主数据。
     *
     * <p>用于第 14 阶段确认 schema 初始化是否成功，以及后续 MediaCrawler 采集范围是否正确。</p>
     *
     * @return 国家主数据列表
     */
    @GetMapping("/pois/countries")
    public ResponseEntity<?> listCountries() {
        return ResponseEntity.ok(poiCatalogService.listEnabledCountries());
    }

    /**
     * 查看当前启用的 POI 主数据。
     *
     * <p>{@code countryCodes} 支持逗号分隔，例如 {@code FR,IT}。
     * 这个接口只读数据库，不会触发采集任务。</p>
     *
     * @param countryCodes 可选国家代码，逗号分隔；为空时返回全部启用 POI
     * @return POI 主数据列表
     */
    @GetMapping("/pois")
    public ResponseEntity<?> listPois(@RequestParam(required = false) String countryCodes) {
        return ResponseEntity.ok(poiCatalogService.listEnabledPois(parseCountryCodes(countryCodes)));
    }

    /**
     * 预览 MediaCrawler 小红书搜索关键词。
     *
     * <p>HTTP 语义：只生成候选关键词，不写入 {@code base_config.py}，也不运行爬虫。
     * 这样开发者可以先人工审查关键词是否合理，再决定是否进入后续采集。</p>
     *
     * @param request 关键词生成请求
     * @return POI 数量、关键词数量和关键词候选
     */
    @PostMapping("/mediacrawler/keywords/preview")
    public ResponseEntity<?> previewMediacrawlerKeywords(@RequestBody MediacrawlerKeywordPreviewRequest request) {
        MediacrawlerKeywordPreviewRequest safeRequest = request == null
                ? new MediacrawlerKeywordPreviewRequest(null, null, 80)
                : request;
        List<TravelPoiDTO> pois = poiCatalogService.listEnabledPois(safeRequest.countryCodes());
        List<String> keywords = keywordBuilder.buildKeywords(pois, safeRequest.styleTags(), safeRequest.limitOrDefault());
        return ResponseEntity.ok(new MediacrawlerKeywordPreviewResponse(
                pois.size(),
                keywords.size(),
                keywords,
                "本接口只生成 KEYWORDS 候选；TODO(stage14-media-crawler-config-writer)：后续再写入 MediaCrawler base_config.py。"
        ));
    }

    /**
     * 创建一个可追踪的 MediaCrawler 关键词任务。
     *
     * <p>HTTP 语义：创建任务会把关键词和配置预览保存到 MySQL / 内存任务仓库，
     * 但不会写入外部 {@code base_config.py}，也不会启动爬虫。用户需要先审查返回的
     * {@code configPreview}。</p>
     *
     * @param request 任务创建请求
     * @return 已创建的采集任务
     */
    @PostMapping("/mediacrawler/tasks")
    public ResponseEntity<?> createMediacrawlerTask(@RequestBody MediacrawlerTaskCreateRequest request) {
        MediacrawlerTaskCreateRequest safeRequest = request == null
                ? new MediacrawlerTaskCreateRequest(null, null, 80, null)
                : request;
        return ResponseEntity.ok(crawlTaskService.createKeywordTask(
                safeRequest.countryCodes(),
                safeRequest.styleTags(),
                safeRequest.limitOrDefault(),
                safeRequest.baseConfigPath()));
    }

    /**
     * 查询一个 MediaCrawler 关键词任务。
     *
     * <p>用于人工审核关键词、复制配置预览，以及后续串联真实采集和结果导入。</p>
     *
     * @param taskId 任务 ID
     * @return 找到时返回任务；不存在时返回 404
     */
    @GetMapping("/mediacrawler/tasks/{taskId}")
    public ResponseEntity<?> getMediacrawlerTask(@PathVariable String taskId) {
        return crawlTaskService.findTask(taskId)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body("MediaCrawler task not found: " + taskId));
    }

    private static TravelPlanState toState(AdaptiveRagPreviewRequest request) {
        TravelPlanState state = new TravelPlanState();
        state.setUserQuery(request.message());
        state.setDestinations(request.destinations());
        state.setTravelTime(request.travelTime());
        state.setDurationDays(request.durationDays());
        state.setDurationText(request.durationDays() == null ? null : request.durationDays() + "天");

        TravelRequirementSpec spec = new TravelRequirementSpec();
        spec.setOriginalMessage(request.message());
        spec.setDestinations(request.destinations());
        spec.setDepartureCity(request.departureCity());
        spec.setStartDateText(request.travelTime());
        spec.setDurationDays(request.durationDays());
        spec.setBudgetAmount(request.budgetAmount());
        spec.setBudgetCurrency(request.budgetCurrency());
        spec.setPreferences(request.preferences());
        spec.setAvoidances(request.avoidances());
        spec.setTravelStyle(request.travelStyle());
        spec.setAccommodationPreference(request.accommodationPreference());
        spec.setTransportPreference(request.transportPreference());
        spec.setSpecialNotes(request.specialNotes());
        state.setRequirementSpec(spec);
        return state;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static List<String> parseCountryCodes(String countryCodes) {
        List<String> result = new ArrayList<>();
        if (!hasText(countryCodes)) {
            return result;
        }
        for (String countryCode : countryCodes.split(",")) {
            if (hasText(countryCode)) {
                result.add(countryCode.trim().toUpperCase());
            }
        }
        return result;
    }

    /**
     * Adaptive RAG 调试请求体。
     *
     * <p>字段与前端需求表保持接近，方便直接把页面里的结构化需求复制过来测试分类和检索策略。</p>
     */
    public record AdaptiveRagPreviewRequest(
            String message,
            List<String> destinations,
            String departureCity,
            String travelTime,
            Integer durationDays,
            BigDecimal budgetAmount,
            String budgetCurrency,
            List<String> preferences,
            List<String> avoidances,
            String travelStyle,
            String accommodationPreference,
            String transportPreference,
            String specialNotes
    ) {
    }

    /**
     * MediaCrawler 关键词预览请求。
     *
     * <p>countryCodes 为空时使用全部启用 POI；styleTags 为空时由关键词生成器使用默认采集角度。</p>
     */
    public record MediacrawlerKeywordPreviewRequest(
            List<String> countryCodes,
            List<String> styleTags,
            Integer limit
    ) {
        int limitOrDefault() {
            return limit == null ? 80 : limit;
        }
    }

    /**
     * MediaCrawler 关键词预览响应。
     *
     * <p>不返回完整 POI 列表，避免调试响应过大；需要看 POI 时调用 {@code GET /pois}。</p>
     */
    public record MediacrawlerKeywordPreviewResponse(
            int poiCount,
            int keywordCount,
            List<String> keywords,
            String note
    ) {
    }

    /**
     * MediaCrawler 任务创建请求。
     *
     * <p>baseConfigPath 为空时，服务会使用当前项目记录的默认 MediaCrawler 路径。
     * 本阶段只保存路径和生成配置预览，不直接写文件。</p>
     */
    public record MediacrawlerTaskCreateRequest(
            List<String> countryCodes,
            List<String> styleTags,
            Integer limit,
            String baseConfigPath
    ) {
        int limitOrDefault() {
            return limit == null ? 80 : limit;
        }
    }
}
