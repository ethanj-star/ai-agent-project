package com.travel.agent.core.etl;

import com.travel.agent.core.dto.TravelPoiDTO;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * MediaCrawler 关键词生成器（Core ETL 层 - 小红书采集前置步骤）。
 *
 * <p>系统架构位置：KnowledgeController -> <b>MediacrawlerKeywordBuilder</b> -> MediaCrawler base_config.py(KEYWORDS)</p>
 *
 * <p>职责：
 * <ul>
 *   <li>根据 POI 主数据和旅行风格生成小红书搜索关键词候选。</li>
 *   <li>保持确定性输出，方便人工审核后再写入 MediaCrawler 配置。</li>
 *   <li>只生成关键词，不直接运行爬虫，避免第 14 阶段误触发不可控采集。</li>
 * </ul>
 * </p>
 *
 * <p>TODO(stage14-media-crawler-config-writer)：后续新增 MediacrawlerConfigWriter，
 * 将审核后的关键词安全写入 `C:\Users\DJI\Desktop\red note mediacrawler` 的 base_config.py。</p>
 */
@Component
public class MediacrawlerKeywordBuilder {

    /** 没有传入风格时使用的默认采集角度。 */
    private static final List<String> DEFAULT_STYLE_TAGS = List.of("攻略", "避坑", "小众", "交通", "门票");

    /**
     * 根据 POI 列表和风格标签生成关键词。
     *
     * <p>生成策略：优先使用中文/本地名，其次英文名；关键词会包含景点、城市、风格、攻略、
     * 避坑、门票、交通等组合。返回结果保持去重和稳定顺序，方便人工复制到 MediaCrawler。</p>
     *
     * @param pois      POI 主数据
     * @param styleTags 旅行风格或采集角度；为空时使用默认角度
     * @param limit     最多返回多少个关键词
     * @return 关键词候选列表
     */
    public List<String> buildKeywords(List<TravelPoiDTO> pois, List<String> styleTags, int limit) {
        List<String> safeStyles = normalizeStyles(styleTags);
        int safeLimit = Math.min(200, Math.max(1, limit <= 0 ? 80 : limit));
        Set<String> keywords = new LinkedHashSet<>();

        if (pois == null || pois.isEmpty()) {
            return List.of();
        }

        for (TravelPoiDTO poi : pois) {
            if (poi == null || !poi.isEnabled()) {
                continue;
            }
            String displayName = firstText(poi.getLocalName(), poi.getPoiName());
            String cityName = poi.getCityName();

            if (!hasText(displayName)) {
                continue;
            }

            addKeyword(keywords, displayName + " 攻略");
            addKeyword(keywords, displayName + " 避坑");
            if (hasText(cityName)) {
                addKeyword(keywords, cityName + " " + displayName + " 攻略");
                addKeyword(keywords, cityName + " 旅行攻略");
            }

            for (String style : safeStyles) {
                addKeyword(keywords, displayName + " " + style + " 攻略");
                if (hasText(cityName)) {
                    addKeyword(keywords, cityName + " " + style + " 旅行");
                }
            }

            for (String tag : poi.getTags()) {
                addTagBasedKeyword(keywords, displayName, cityName, tag);
            }

            if (keywords.size() >= safeLimit) {
                break;
            }
        }

        return new ArrayList<>(keywords).stream().limit(safeLimit).toList();
    }

    private static void addTagBasedKeyword(Set<String> keywords, String displayName, String cityName, String tag) {
        if (!hasText(tag)) {
            return;
        }
        String normalized = tag.trim().toLowerCase();
        switch (normalized) {
            case "ticket" -> {
                addKeyword(keywords, displayName + " 门票 预约");
                addKeyword(keywords, displayName + " 门票 避坑");
            }
            case "crowd" -> addKeyword(keywords, displayName + " 避开人多");
            case "food" -> addKeyword(keywords, firstText(cityName, displayName) + " 美食 攻略");
            case "photo" -> addKeyword(keywords, displayName + " 拍照 机位");
            case "transport", "transit", "train" -> addKeyword(keywords, displayName + " 交通 攻略");
            case "mountain", "nature", "lake", "coast", "hiking" -> addKeyword(keywords, displayName + " 自然风景 攻略");
            case "museum", "history", "classic" -> addKeyword(keywords, displayName + " 必看 攻略");
            default -> {
                // 标签来自 POI 运营表，未知标签也保留为关键词的一部分，便于后续观察是否有价值。
                addKeyword(keywords, displayName + " " + tag + " 攻略");
            }
        }
    }

    private static List<String> normalizeStyles(List<String> styleTags) {
        List<String> styles = new ArrayList<>();
        List<String> source = styleTags == null || styleTags.isEmpty() ? DEFAULT_STYLE_TAGS : styleTags;
        for (String style : source) {
            if (hasText(style)) {
                styles.add(style.trim());
            }
        }
        return styles.isEmpty() ? DEFAULT_STYLE_TAGS : styles;
    }

    private static void addKeyword(Set<String> keywords, String keyword) {
        if (hasText(keyword)) {
            keywords.add(keyword.trim().replaceAll("\\s+", " "));
        }
    }

    private static String firstText(String first, String second) {
        if (hasText(first)) {
            return first.trim();
        }
        return hasText(second) ? second.trim() : null;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
