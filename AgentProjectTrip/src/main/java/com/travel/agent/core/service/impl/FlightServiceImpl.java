package com.travel.agent.core.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.travel.agent.core.dto.FlightDTO;
import com.travel.agent.core.service.FlightService;
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
import java.util.Optional;

/**
 * 航班业务服务实现类（Service 层 - 数据获取核心）
 *
 * <p>系统架构位置：Tools 层 → <b>Service 层</b> → 外部 SerpApi</p>
 *
 * <p>职责：
 * <ul>
 *   <li>通过 SerpApi 的 Google Flights 引擎获取真实的航班价格数据。</li>
 *   <li>使用 Spring Cache（底层为 Redis）对查询结果进行缓存，TTL 由
 *       {@code spring.cache.redis.time-to-live} 统一配置（当前为 10 分钟），
 *       在降低 API 调用频次的同时保证价格数据的时效性。</li>
 *   <li>对所有外部调用加装完整的异常防护，确保 SerpApi 故障不会向上传播
 *       导致整个 Agent 推理链崩溃。</li>
 * </ul>
 * </p>
 */
@Service
public class FlightServiceImpl implements FlightService {

    private static final Logger log = LoggerFactory.getLogger(FlightServiceImpl.class);

    /** SerpApi 搜索接口的基础地址（此处通过 UriBuilder 动态构建，仅作文档参考） */
    private static final String SERPAPI_BASE = "https://serpapi.com/search.json";

    /** 单次查询最多返回的航班条数，避免大模型上下文窗口被冗长数据占满 */
    private static final int MAX_RESULTS = 3;

    private final RestClient restClient;
    private final String apiKey;

    /**
     * 构造器注入 RestClient 和 SerpApi 密钥。
     *
     * <p>使用 {@link RestClient.Builder} 而非直接注入 {@link RestClient}，
     * 是为了允许 Spring Boot 自动配置（如 SSL、超时、拦截器）在构建阶段生效。</p>
     *
     * @param builder Spring Boot 自动配置提供的 {@link RestClient.Builder}
     * @param apiKey  从环境变量 {@code SERPAPI_KEY} 读取的 SerpApi 鉴权密钥，
     *                由 {@code application.properties} 中的
     *                {@code serpapi.api-key=${SERPAPI_KEY}} 桥接注入
     */
    public FlightServiceImpl(RestClient.Builder builder,
                             @Value("${serpapi.api-key}") String apiKey) {
        this.restClient = builder.build();
        this.apiKey = apiKey;
    }

    /**
     * 根据 ID 查询单条航班记录（当前版本暂未实现，预留扩展接口）。
     *
     * @param id 航班唯一标识符
     * @return 始终返回 {@link Optional#empty()}
     */
    @Override
    public Optional<FlightDTO> findById(String id) {
        return Optional.empty();
    }

    /**
     * 查询指定路线的单程航班列表，结果将被 Redis 缓存。
     *
     * <p>缓存策略：以 {@code origin:destination:date} 为复合缓存键，命中缓存时
     * 直接返回，不发起任何 HTTP 请求；缓存未命中时调用 SerpApi，结果写入 Redis。</p>
     *
     * <p>SerpApi 请求参数说明：
     * <ul>
     *   <li>{@code engine=google_flights}：指定使用 Google Flights 数据引擎</li>
     *   <li>{@code departure_id} / {@code arrival_id}：IATA 三字码，标识出发地和目的地机场</li>
     *   <li>{@code outbound_date}：出发日期，格式 YYYY-MM-DD</li>
     *   <li>{@code hl=zh-cn}：返回简体中文的航司名称和说明</li>
     *   <li>{@code currency=EUR}：统一以欧元（€）表示价格，便于跨航司比价</li>
     *   <li>{@code type=2}：<b>关键参数</b>，指定搜索单程票（One-way）；
     *       默认值 type=1 为往返票，不传此参数会因缺少 return_date 导致 400 错误</li>
     *   <li>{@code api_key}：SerpApi 鉴权密钥，置于参数列表末尾</li>
     * </ul>
     * </p>
     *
     * @param origin      出发地 IATA 三字码，例如 "DUB"
     * @param destination 目的地 IATA 三字码，例如 "CDG"
     * @param date        出发日期，格式 YYYY-MM-DD
     * @return 最多 {@value MAX_RESULTS} 条航班信息列表；任何异常情况下返回空列表
     */
    @Override
    @Cacheable(cacheNames = "flights", key = "#origin + ':' + #destination + ':' + #date")
    public List<FlightDTO> searchFlights(String origin, String destination, String date) {
        log.info("[SerpApi] 查询航班 {} → {}，日期：{}", origin, destination, date);

        try {
            // 使用 UriBuilder 构建带查询参数的 SerpApi 请求 URL，避免手动拼接字符串导致的编码问题
            JsonNode root = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .scheme("https")
                            .host("serpapi.com")
                            .path("/search.json")
                            .queryParam("engine", "google_flights")
                            .queryParam("departure_id", origin)
                            .queryParam("arrival_id", destination)
                            .queryParam("outbound_date", date)
                            .queryParam("hl", "zh-cn")
                            .queryParam("currency", "EUR")
                            // type=2：强制搜索单程票，避免因缺少 return_date 参数导致 400 Bad Request
                            .queryParam("type", "2")
                            .queryParam("api_key", apiKey)
                            .build())
                    .retrieve()
                    // 直接将响应体反序列化为 Jackson JsonNode 树，便于后续灵活的路径遍历
                    .body(JsonNode.class);

            if (root == null) {
                log.warn("[SerpApi] 响应体为空，返回空列表");
                return Collections.emptyList();
            }

            // 优先取 best_flights（Google Flights 优选航班），若为空则降级到 other_flights（备选航班）
            // 使用 JsonNode.path() 而非 get()，路径不存在时返回 MissingNode 而非 null，防止空指针
            JsonNode flightList = root.path("best_flights");
            if (flightList.isMissingNode() || !flightList.isArray() || flightList.isEmpty()) {
                flightList = root.path("other_flights");
            }
            if (flightList.isMissingNode() || !flightList.isArray() || flightList.isEmpty()) {
                log.warn("[SerpApi] 未找到任何航班数据，路由：{} → {}", origin, destination);
                return Collections.emptyList();
            }

            List<FlightDTO> results = new ArrayList<>();
            for (int i = 0; i < Math.min(MAX_RESULTS, flightList.size()); i++) {
                JsonNode entry = flightList.get(i);

                // 航司名称：SerpApi 返回的每个航班条目包含 flights 数组（代表各航段）
                // 取第一航段（flights[0]）的 airline 字段作为主航司展示名
                String airline = "未知航司";
                JsonNode flightsNode = entry.path("flights");
                if (flightsNode.isArray() && !flightsNode.isEmpty()) {
                    JsonNode firstLeg = flightsNode.get(0);
                    if (firstLeg.has("airline")) {
                        // asText(defaultValue) 在节点为 null 或类型不匹配时返回默认值，保证健壮性
                        airline = firstLeg.path("airline").asText(airline);
                    }
                }

                // 价格：位于条目顶层的 price 字段，单位为请求时指定的货币（EUR）
                double price = 0.0;
                if (entry.has("price")) {
                    price = entry.path("price").asDouble(0.0);
                }

                // 生成本地唯一 ID，格式：serpapi-{出发地}-{目的地}-{序号}
                String flightId = "serpapi-" + origin + "-" + destination + "-" + i;
                results.add(new FlightDTO(flightId, origin, destination, airline, price));

                log.debug("[SerpApi] 航班[{}]：{} | 价格：{}EUR", i, airline, price);
            }

            log.info("[SerpApi] 查询成功，共返回 {} 条航班", results.size());
            // 返回不可变列表，防止调用方意外修改缓存对象
            return Collections.unmodifiableList(results);

        } catch (RestClientException e) {
            // 网络故障、HTTP 4xx/5xx、连接超时等 RestClient 层面的错误
            log.error("[SerpApi] HTTP 请求失败，路由：{} → {}，错误：{}", origin, destination, e.getMessage(), e);
            return Collections.emptyList();
        } catch (Exception e) {
            // JSON 结构异常、字段类型不匹配等解析层面的兜底捕获
            // 任何异常都返回空列表而非向上抛出，保证 Agent 推理链的鲁棒性
            log.error("[SerpApi] 解析响应时发生意外错误，路由：{} → {}，错误：{}", origin, destination, e.getMessage(), e);
            return Collections.emptyList();
        }
    }
}
