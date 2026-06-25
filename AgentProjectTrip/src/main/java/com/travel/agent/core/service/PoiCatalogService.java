package com.travel.agent.core.service;

import com.travel.agent.core.dto.TravelCountryDTO;
import com.travel.agent.core.dto.TravelPoiDTO;

import java.util.List;

/**
 * 旅行 POI 主数据服务（Core 层 - RAG 知识运营入口）。
 *
 * <p>系统架构位置：KnowledgeController / MediaCrawlerKeywordBuilder -> <b>PoiCatalogService</b> -> MySQL 或内存实现</p>
 *
 * <p>职责：
 * <ul>
 *   <li>读取当前系统维护的国家和景点主数据。</li>
 *   <li>为 MediaCrawler 关键词生成提供稳定 POI 输入。</li>
 *   <li>后续用于统计哪些景点缺少攻略、哪些景点需要重新采集。</li>
 * </ul>
 * </p>
 */
public interface PoiCatalogService {

    /**
     * 列出当前启用的国家。
     *
     * @return 国家主数据列表
     */
    List<TravelCountryDTO> listEnabledCountries();

    /**
     * 按国家代码列出启用的 POI。
     *
     * @param countryCodes 国家代码列表；为空时返回所有启用 POI
     * @return POI 主数据列表
     */
    List<TravelPoiDTO> listEnabledPois(List<String> countryCodes);
}
