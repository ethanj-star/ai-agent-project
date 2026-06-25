package com.travel.agent.core.service.impl;

import com.travel.agent.core.dto.TravelCountryDTO;
import com.travel.agent.core.dto.TravelPoiDTO;
import com.travel.agent.core.service.PoiCatalogService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 内存版 POI 主数据服务。
 *
 * <p>系统架构位置：KnowledgeController -> PoiCatalogService -> <b>InMemoryPoiCatalogService</b></p>
 *
 * <p>职责：在没有启用 JDBC 实现时提供开发兜底数据，保证第 14 阶段关键词生成接口仍可手动测试。
 * 生产和正常本地开发应优先使用 {@link JdbcPoiCatalogService} 读取 MySQL 主数据。</p>
 */
@Service
@ConditionalOnMissingBean(PoiCatalogService.class)
public class InMemoryPoiCatalogService implements PoiCatalogService {

    private final List<TravelCountryDTO> countries = List.of(
            country("FR", "France", "法国"),
            country("IT", "Italy", "意大利"),
            country("CH", "Switzerland", "瑞士")
    );

    private final List<TravelPoiDTO> pois = List.of(
            poi("poi-fr-paris-louvre", "FR", "Paris", "Louvre Museum", "卢浮宫",
                    List.of("museum", "ticket", "crowd"), 5),
            poi("poi-it-rome-colosseum", "IT", "Rome", "Colosseum", "罗马斗兽场",
                    List.of("history", "ticket", "crowd"), 5),
            poi("poi-ch-jungfrau", "CH", "Jungfrau Region", "Jungfrau Region", "少女峰地区",
                    List.of("mountain", "train", "weather-risk"), 5)
    );

    @Override
    public List<TravelCountryDTO> listEnabledCountries() {
        return countries;
    }

    @Override
    public List<TravelPoiDTO> listEnabledPois(List<String> countryCodes) {
        List<String> cleanedCodes = cleanCodes(countryCodes);
        if (cleanedCodes.isEmpty()) {
            return pois;
        }
        List<TravelPoiDTO> result = new ArrayList<>();
        for (TravelPoiDTO poi : pois) {
            if (cleanedCodes.contains(poi.getCountryCode())) {
                result.add(poi);
            }
        }
        return result;
    }

    private static TravelCountryDTO country(String code, String name, String localName) {
        TravelCountryDTO country = new TravelCountryDTO();
        country.setCountryCode(code);
        country.setCountryName(name);
        country.setLocalName(localName);
        country.setEnabled(true);
        return country;
    }

    private static TravelPoiDTO poi(String id,
                                    String countryCode,
                                    String city,
                                    String name,
                                    String localName,
                                    List<String> tags,
                                    int popularity) {
        TravelPoiDTO poi = new TravelPoiDTO();
        poi.setPoiId(id);
        poi.setCountryCode(countryCode);
        poi.setCityName(city);
        poi.setPoiName(name);
        poi.setLocalName(localName);
        poi.setTags(tags);
        poi.setPopularityLevel(popularity);
        poi.setEnabled(true);
        poi.setRagCoverageStatus("MISSING");
        return poi;
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
