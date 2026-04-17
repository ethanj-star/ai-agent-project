package com.travel.agent.core.service;

import com.travel.agent.core.dto.FlightDTO;

import java.util.List;
import java.util.Optional;

/**
 * 航班业务服务接口（Service 层 - 核心契约定义）
 *
 * <p>定义航班相关的业务能力边界。上层的 {@code FlightTools}（AI 工具桥接器）和
 * {@code TravelController}（HTTP 接口层）均依赖此接口，而非具体实现类，
 * 遵循依赖倒置原则，便于后续切换数据源（如替换 SerpApi 为其他航班服务商）。</p>
 */
public interface FlightService {

    /**
     * 根据唯一标识符查询单条航班信息。
     *
     * @param id 航班唯一标识符
     * @return 对应的航班 DTO；若不存在则返回 {@link Optional#empty()}
     */
    Optional<FlightDTO> findById(String id);

    /**
     * 查询指定路线的单程航班列表。
     *
     * @param origin      出发地 IATA 三字码，例如 {@code "DUB"}
     * @param destination 目的地 IATA 三字码，例如 {@code "CDG"}
     * @param date        出发日期，格式 YYYY-MM-DD，例如 {@code "2026-05-20"}
     * @return 可用航班列表；若无结果或查询失败则返回空列表
     */
    List<FlightDTO> searchFlights(String origin, String destination, String date);
}
