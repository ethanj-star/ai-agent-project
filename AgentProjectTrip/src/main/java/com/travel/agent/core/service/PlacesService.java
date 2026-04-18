package com.travel.agent.core.service;

import com.travel.agent.core.dto.AttractionDTO;
import com.travel.agent.core.dto.HotelDTO;

import java.util.List;

/**
 * 地点业务服务接口（Service 层 - 核心契约定义）
 *
 * <p>定义酒店搜索和景点搜索两类业务能力边界。上层的 {@code PlacesTools}（AI 工具桥接器）
 * 依赖此接口，而非具体实现类，遵循依赖倒置原则，便于后续切换数据源
 * （如替换 SerpApi 为 Google Places API 等其他服务商）。</p>
 */
public interface PlacesService {

    /**
     * 根据城市和入住/退房日期查询酒店列表。
     *
     * <p>此方法保证不抛出异常：若外部 API 调用失败，将返回空列表，
     * 而不是向调用方传播异常。</p>
     *
     * @param city         目标城市名称（英文），例如 {@code "Paris"}、{@code "Rome"}
     * @param checkInDate  入住日期，格式 YYYY-MM-DD，例如 {@code "2026-06-01"}
     * @param checkOutDate 退房日期，格式 YYYY-MM-DD，例如 {@code "2026-06-03"}
     * @return 最多 3 条酒店信息列表；若无结果或查询失败则返回空列表
     */
    List<HotelDTO> searchHotels(String city, String checkInDate, String checkOutDate);

    /**
     * 根据城市名称查询热门景点列表。
     *
     * <p>此方法保证不抛出异常：若外部 API 调用失败，将返回空列表，
     * 而不是向调用方传播异常。</p>
     *
     * @param city 目标城市名称（英文），例如 {@code "London"}、{@code "Dublin"}
     * @return 最多 3 条热门景点信息列表；若无结果或查询失败则返回空列表
     */
    List<AttractionDTO> searchAttractions(String city);
}
