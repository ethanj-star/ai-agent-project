package com.travel.agent.core.service.impl;

import com.travel.agent.core.dto.TravelPoiDTO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * InMemoryPoiCatalogService 的单元测试。
 *
 * <p>重点验证非 JDBC 环境下的第 14 阶段 POI 兜底数据可用，方便临时切换 memory 模式排查问题。</p>
 */
class InMemoryPoiCatalogServiceTest {

    @Test
    void listEnabledCountriesReturnsSeedCountries() {
        InMemoryPoiCatalogService service = new InMemoryPoiCatalogService();

        assertThat(service.listEnabledCountries())
                .extracting("countryCode")
                .containsExactly("FR", "IT", "CH");
    }

    @Test
    void listEnabledPoisCanFilterByCountryCode() {
        InMemoryPoiCatalogService service = new InMemoryPoiCatalogService();

        List<TravelPoiDTO> pois = service.listEnabledPois(List.of("FR"));

        assertThat(pois).isNotEmpty();
        assertThat(pois).allMatch(poi -> "FR".equals(poi.getCountryCode()));
    }
}
