package com.travel.agent.core.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.agent.core.dto.TravelCountryDTO;
import com.travel.agent.core.dto.TravelPoiDTO;
import com.travel.agent.core.service.PoiCatalogService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * JDBC 版 POI 主数据服务。
 *
 * <p>系统架构位置：KnowledgeController -> PoiCatalogService -> <b>JdbcPoiCatalogService</b> -> MySQL travel_pois</p>
 *
 * <p>职责：
 * <ul>
 *   <li>从 MAMP MySQL 读取第 14 阶段维护的国家和景点主数据。</li>
 *   <li>把数据库中的 tags_json 转换成 Java List，供关键词生成器使用。</li>
 *   <li>只负责读取主数据，不负责真实爬虫执行和 Pinecone upsert。</li>
 * </ul>
 * </p>
 */
@Service
@ConditionalOnProperty(name = "travel.persistence.type", havingValue = "jdbc")
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class JdbcPoiCatalogService implements PoiCatalogService {

    /** MySQL 查询模板。 */
    private final JdbcTemplate jdbcTemplate;

    /** JSON 解析器，用于解析 travel_pois.tags_json。 */
    private final ObjectMapper objectMapper;

    public JdbcPoiCatalogService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<TravelCountryDTO> listEnabledCountries() {
        return jdbcTemplate.query("""
                        SELECT country_code, country_name, local_name, enabled
                        FROM travel_countries
                        WHERE enabled = TRUE
                        ORDER BY country_code
                        """,
                (rs, rowNum) -> mapCountry(rs));
    }

    @Override
    public List<TravelPoiDTO> listEnabledPois(List<String> countryCodes) {
        List<String> cleanedCodes = cleanCodes(countryCodes);
        if (cleanedCodes.isEmpty()) {
            return jdbcTemplate.query("""
                            SELECT poi_id, country_code, city_name, poi_name, local_name,
                                   tags_json, popularity_level, enabled, rag_coverage_status, notes
                            FROM travel_pois
                            WHERE enabled = TRUE
                            ORDER BY country_code, popularity_level DESC, city_name, poi_name
                            """,
                    (rs, rowNum) -> mapPoi(rs));
        }

        String placeholders = String.join(",", cleanedCodes.stream().map(code -> "?").toList());
        List<Object> args = new ArrayList<>(cleanedCodes);
        return jdbcTemplate.query("""
                        SELECT poi_id, country_code, city_name, poi_name, local_name,
                               tags_json, popularity_level, enabled, rag_coverage_status, notes
                        FROM travel_pois
                        WHERE enabled = TRUE AND country_code IN (%s)
                        ORDER BY country_code, popularity_level DESC, city_name, poi_name
                        """.formatted(placeholders),
                (rs, rowNum) -> mapPoi(rs),
                args.toArray());
    }

    private TravelCountryDTO mapCountry(ResultSet rs) throws SQLException {
        TravelCountryDTO country = new TravelCountryDTO();
        country.setCountryCode(rs.getString("country_code"));
        country.setCountryName(rs.getString("country_name"));
        country.setLocalName(rs.getString("local_name"));
        country.setEnabled(rs.getBoolean("enabled"));
        return country;
    }

    private TravelPoiDTO mapPoi(ResultSet rs) throws SQLException {
        TravelPoiDTO poi = new TravelPoiDTO();
        poi.setPoiId(rs.getString("poi_id"));
        poi.setCountryCode(rs.getString("country_code"));
        poi.setCityName(rs.getString("city_name"));
        poi.setPoiName(rs.getString("poi_name"));
        poi.setLocalName(rs.getString("local_name"));
        poi.setTags(parseTags(rs.getString("tags_json")));
        poi.setPopularityLevel(rs.getInt("popularity_level"));
        poi.setEnabled(rs.getBoolean("enabled"));
        poi.setRagCoverageStatus(rs.getString("rag_coverage_status"));
        poi.setNotes(rs.getString("notes"));
        return poi;
    }

    private List<String> parseTags(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            // tags 解析失败不应让整个 POI 列表不可用；返回空标签，后续关键词仍能用城市和景点名生成。
            return List.of();
        }
    }

    private static List<String> cleanCodes(List<String> countryCodes) {
        List<String> cleaned = new ArrayList<>();
        if (countryCodes == null) {
            return cleaned;
        }
        for (String countryCode : countryCodes) {
            if (countryCode != null && !countryCode.isBlank()) {
                cleaned.add(countryCode.trim().toUpperCase());
            }
        }
        return cleaned;
    }
}
