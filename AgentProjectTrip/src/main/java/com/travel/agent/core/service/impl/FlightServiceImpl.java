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

@Service
public class FlightServiceImpl implements FlightService {

    private static final Logger log = LoggerFactory.getLogger(FlightServiceImpl.class);
    private static final String SERPAPI_BASE = "https://serpapi.com/search.json";
    private static final int MAX_RESULTS = 3;

    private final RestClient restClient;
    private final String apiKey;

    public FlightServiceImpl(RestClient.Builder builder,
                             @Value("${serpapi.api-key}") String apiKey) {
        this.restClient = builder.build();
        this.apiKey = apiKey;
    }

    @Override
    public Optional<FlightDTO> findById(String id) {
        return Optional.empty();
    }

    @Override
    @Cacheable(cacheNames = "flights", key = "#origin + ':' + #destination + ':' + #date")
    public List<FlightDTO> searchFlights(String origin, String destination, String date) {
        log.info("[SerpApi] 查询航班 {} → {}，日期：{}", origin, destination, date);

        try {
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
                            .queryParam("type", "2")
                            .queryParam("api_key", apiKey)
                            .build())
                    .retrieve()
                    .body(JsonNode.class);

            if (root == null) {
                log.warn("[SerpApi] 响应体为空，返回空列表");
                return Collections.emptyList();
            }

            // 优先取 best_flights，若为空则降级到 other_flights
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

                // 航司名称：取第一段航班信息中的 airline 字段
                String airline = "未知航司";
                JsonNode flightsNode = entry.path("flights");
                if (flightsNode.isArray() && !flightsNode.isEmpty()) {
                    JsonNode firstLeg = flightsNode.get(0);
                    if (firstLeg.has("airline")) {
                        airline = firstLeg.path("airline").asText(airline);
                    }
                }

                // 价格：直接取顶层 price 字段
                double price = 0.0;
                if (entry.has("price")) {
                    price = entry.path("price").asDouble(0.0);
                }

                String flightId = "serpapi-" + origin + "-" + destination + "-" + i;
                results.add(new FlightDTO(flightId, origin, destination, airline, price));

                log.debug("[SerpApi] 航班[{}]：{} | 价格：{}EUR", i, airline, price);
            }

            log.info("[SerpApi] 查询成功，共返回 {} 条航班", results.size());
            return Collections.unmodifiableList(results);

        } catch (RestClientException e) {
            log.error("[SerpApi] HTTP 请求失败，路由：{} → {}，错误：{}", origin, destination, e.getMessage(), e);
            return Collections.emptyList();
        } catch (Exception e) {
            log.error("[SerpApi] 解析响应时发生意外错误，路由：{} → {}，错误：{}", origin, destination, e.getMessage(), e);
            return Collections.emptyList();
        }
    }
}
