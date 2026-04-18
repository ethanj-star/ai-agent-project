package com.travel.agent.core.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.travel.agent.core.dto.AttractionDTO;
import com.travel.agent.core.dto.HotelDTO;
import com.travel.agent.core.service.PlacesService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 地点业务服务实现类（Service 层 - 数据获取核心）
 *
 * <p>系统架构位置：Tools 层 → <b>Service 层</b> → 外部 SerpApi</p>
 *
 * <p>职责：
 * <ul>
 *   <li>复用已有的 SerpApi 密钥，通过 Google Hotels 引擎和 Google Local 引擎
 *       分别获取酒店和景点真实数据。</li>
 *   <li>使用 Spring Cache（底层为 Redis）对查询结果进行缓存，TTL 由
 *       {@code spring.cache.redis.time-to-live} 统一配置，降低 API 调用频次。</li>
 *   <li>对所有外部调用加装完整的异常防护，确保 SerpApi 故障不会向上传播
 *       导致整个 Agent 推理链崩溃。</li>
 * </ul>
 * </p>
 */
@Service
public class PlacesServiceImpl implements PlacesService {

    private static final Logger log = LoggerFactory.getLogger(PlacesServiceImpl.class);

    /** 单次查询最多返回的条数，避免大模型上下文窗口被冗长数据占满 */
    private static final int MAX_RESULTS = 3;

    /** 酒店价格不可用时的占位字符串 */
    private static final String PRICE_UNAVAILABLE = "价格未知";

    /** 景点简介不可用时的占位字符串 */
    private static final String DESC_UNAVAILABLE = "暂无简介";

    private final RestClient restClient;

    /** 从环境变量 SERPAPI_KEY 读取的 API 密钥，由 application.properties 桥接注入 */
    private final String apiKey;

    /**
     * 构造器注入 RestClient 和 SerpApi 密钥。
     *
     * @param builder Spring Boot 自动配置提供的 {@link RestClient.Builder}
     * @param apiKey  从配置项 {@code serpapi.api-key} 注入的 API 密钥
     */
    public PlacesServiceImpl(RestClient.Builder builder,
                             @Value("${serpapi.api-key}") String apiKey) {
        this.restClient = builder.build();
        this.apiKey = apiKey;
    }

    /**
     * {@inheritDoc}
     *
     * <p>调用 SerpApi 的 {@code google_hotels} 引擎，解析响应中的
     * {@code properties} 数组，提取前 {@value MAX_RESULTS} 条酒店信息。</p>
     *
     * <p>SerpApi 响应字段映射：
     * <ul>
     *   <li>{@code properties[].name}                        → name（酒店名称）</li>
     *   <li>{@code properties[].rate_per_night.lowest}       → price（每晚最低价）</li>
     *   <li>{@code properties[].overall_rating}              → rating（综合评分）</li>
     * </ul>
     * </p>
     */
    @Override
    @Cacheable(value = "hotels", key = "#city + ':' + #checkInDate")
    public List<HotelDTO> searchHotels(String city, String checkInDate, String checkOutDate) {
        log.info("[SerpApi-Hotels] 查询酒店：城市={}，入住={}，退房={}", city, checkInDate, checkOutDate);

        try {
            JsonNode root = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .scheme("https")
                            .host("serpapi.com")
                            .path("/search.json")
                            .queryParam("engine", "google_hotels")
                            .queryParam("q", city)
                            .queryParam("check_in_date", checkInDate)
                            .queryParam("check_out_date", checkOutDate)
                            .queryParam("currency", "EUR")
                            .queryParam("hl", "zh-cn")
                            .queryParam("api_key", apiKey)
                            .build())
                    .retrieve()
                    .body(JsonNode.class);

            if (root == null) {
                log.warn("[SerpApi-Hotels] 响应体为空，城市：{}", city);
                return Collections.emptyList();
            }

            // properties 节点：Google Hotels 引擎的核心结果数组
            JsonNode properties = root.path("properties");
            if (properties.isMissingNode() || !properties.isArray() || properties.isEmpty()) {
                log.warn("[SerpApi-Hotels] 未找到酒店数据，城市：{}", city);
                return Collections.emptyList();
            }

            List<HotelDTO> results = new ArrayList<>();
            for (int i = 0; i < Math.min(MAX_RESULTS, properties.size()); i++) {
                JsonNode hotel = properties.get(i);

                String name = hotel.path("name").asText("未知酒店");

                // rate_per_night.lowest：每晚最低价（含货币符号的字符串，如 "€120"）
                // 使用双层 path() 防止 rate_per_night 节点缺失时抛空指针
                String price = hotel.path("rate_per_night").path("lowest").asText(PRICE_UNAVAILABLE);

                // overall_rating：综合评分，范围 0~5；节点缺失时 asDouble 返回默认值 0.0
                double rating = hotel.path("overall_rating").asDouble(0.0);

                results.add(new HotelDTO(name, price, rating));
                log.debug("[SerpApi-Hotels] 酒店[{}]：{} | 价格：{} | 评分：{}", i, name, price, rating);
            }

            log.info("[SerpApi-Hotels] 查询成功，共返回 {} 条酒店", results.size());
            return Collections.unmodifiableList(results);

        } catch (RestClientException e) {
            // HTTP 4xx/5xx、网络超时等 RestClient 层面的错误
            log.error("[SerpApi-Hotels] HTTP 请求失败，城市：{}，错误：{}", city, e.getMessage(), e);
            return Collections.emptyList();
        } catch (Exception e) {
            // JSON 结构异常等解析层面的兜底捕获，保证 Agent 推理链鲁棒性
            log.error("[SerpApi-Hotels] 解析响应时发生意外错误，城市：{}，错误：{}", city, e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>调用 SerpApi 的 {@code google_local} 引擎，以 "top attractions in {city}" 为查询词，
     * 解析响应中的 {@code local_results} 数组，提取前 {@value MAX_RESULTS} 个热门景点。</p>
     *
     * <p>SerpApi 响应字段映射：
     * <ul>
     *   <li>{@code local_results[].title}  → name（景点名称）</li>
     *   <li>{@code local_results[].rating} → rating（用户评分）</li>
     *   <li>{@code local_results[].type}   → description（景点类型/分类标签）</li>
     * </ul>
     * </p>
     */
    @Override
    @Cacheable(value = "attractions", key = "#city")
    public List<AttractionDTO> searchAttractions(String city) {
        log.info("[SerpApi-Local] 查询热门景点：城市={}", city);

        try {
            JsonNode root = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .scheme("https")
                            .host("serpapi.com")
                            .path("/search.json")
                            .queryParam("engine", "google_local")
                            // 拼接查询词：让 Google Local 聚焦在景点搜索而非通用地点
                            .queryParam("q", "top attractions in " + city)
                            .queryParam("hl", "zh-cn")
                            .queryParam("api_key", apiKey)
                            .build())
                    .retrieve()
                    .body(JsonNode.class);

            if (root == null) {
                log.warn("[SerpApi-Local] 响应体为空，城市：{}", city);
                return Collections.emptyList();
            }

            // local_results 节点：Google Local 引擎的核心结果数组
            JsonNode localResults = root.path("local_results");
            if (localResults.isMissingNode() || !localResults.isArray() || localResults.isEmpty()) {
                log.warn("[SerpApi-Local] 未找到景点数据，城市：{}", city);
                return Collections.emptyList();
            }

            List<AttractionDTO> results = new ArrayList<>();
            for (int i = 0; i < Math.min(MAX_RESULTS, localResults.size()); i++) {
                JsonNode attraction = localResults.get(i);

                // title：景点名称（Google Local 使用 title 而非 name 字段）
                String name = attraction.path("title").asText("未知景点");

                // rating：用户综合评分，范围 0~5
                double rating = attraction.path("rating").asDouble(0.0);

                // type：景点分类标签，如 "博物馆"、"历史地标"、"主题公园"
                String description = attraction.path("type").asText(DESC_UNAVAILABLE);

                results.add(new AttractionDTO(name, rating, description));
                log.debug("[SerpApi-Local] 景点[{}]：{} | 评分：{} | 类型：{}", i, name, rating, description);
            }

            log.info("[SerpApi-Local] 查询成功，共返回 {} 个景点", results.size());
            return Collections.unmodifiableList(results);

        } catch (RestClientException e) {
            log.error("[SerpApi-Local] HTTP 请求失败，城市：{}，错误：{}", city, e.getMessage(), e);
            return Collections.emptyList();
        } catch (Exception e) {
            log.error("[SerpApi-Local] 解析响应时发生意外错误，城市：{}，错误：{}", city, e.getMessage(), e);
            return Collections.emptyList();
        }
    }
}
