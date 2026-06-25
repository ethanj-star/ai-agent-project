package com.travel.agent.core.etl;

import com.travel.agent.core.dto.TravelPoiDTO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MediacrawlerKeywordBuilder 的单元测试。
 *
 * <p>重点验证第 14 阶段的小红书关键词候选生成逻辑：输入 POI 和风格标签后，
 * 应输出稳定、去重、可人工审核的关键词，而不是直接触发爬虫。</p>
 */
class MediacrawlerKeywordBuilderTest {

    @Test
    void buildKeywordsUsesPoiNameCityStyleAndTags() {
        TravelPoiDTO poi = new TravelPoiDTO();
        poi.setPoiId("poi-fr-paris-louvre");
        poi.setCountryCode("FR");
        poi.setCityName("Paris");
        poi.setPoiName("Louvre Museum");
        poi.setLocalName("卢浮宫");
        poi.setTags(List.of("ticket", "crowd", "museum"));
        poi.setEnabled(true);

        MediacrawlerKeywordBuilder builder = new MediacrawlerKeywordBuilder();
        List<String> keywords = builder.buildKeywords(List.of(poi), List.of("亲子", "小众"), 20);

        assertThat(keywords)
                .contains("卢浮宫 攻略")
                .contains("卢浮宫 亲子 攻略")
                .contains("卢浮宫 门票 预约")
                .contains("卢浮宫 避开人多")
                .doesNotHaveDuplicates();
    }

    @Test
    void buildKeywordsRespectsLimit() {
        TravelPoiDTO poi = new TravelPoiDTO();
        poi.setPoiId("poi-it-rome-colosseum");
        poi.setCountryCode("IT");
        poi.setCityName("Rome");
        poi.setPoiName("Colosseum");
        poi.setLocalName("罗马斗兽场");
        poi.setTags(List.of("ticket", "crowd", "classic"));
        poi.setEnabled(true);

        MediacrawlerKeywordBuilder builder = new MediacrawlerKeywordBuilder();
        List<String> keywords = builder.buildKeywords(List.of(poi), List.of("避坑", "门票", "交通"), 3);

        assertThat(keywords).hasSize(3);
    }
}
