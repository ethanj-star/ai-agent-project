package com.travel.agent.ai.tools;

import com.travel.agent.core.dto.AttractionDTO;
import com.travel.agent.core.dto.HotelDTO;
import com.travel.agent.core.service.PlacesService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 地点查询工具集（AI 层 - 工具桥接器）
 *
 * <p>系统架构位置：Agent 层 → <b>Tools 层</b> → Service 层</p>
 *
 * <p>职责：作为 AI 推理世界与地点业务服务之间的桥梁。
 * 本类中被 {@link Tool} 标注的方法会在启动时被 Spring AI 框架自动解析，
 * 其方法签名和 {@code description} 将被序列化为 Function Schema 发送给大模型，
 * 大模型据此理解"何时调用、传入什么参数"。</p>
 *
 * <p>当大模型判断用户需要了解目的地的住宿或游览信息时，框架将自动拦截
 * Function Calling 响应，反射调用对应方法，完成"感知 → 决策 → 行动"闭环。</p>
 */
@Component
public class PlacesTools {

    private final PlacesService placesService;

    /**
     * 构造器注入地点业务服务。
     *
     * @param placesService 地点核心业务服务，负责实际的 SerpApi 调用与缓存管理
     */
    public PlacesTools(PlacesService placesService) {
        this.placesService = placesService;
    }

    /**
     * 查询指定城市在给定日期范围内的酒店价格列表。
     *
     * <p>此工具应在用户询问目的地住宿、酒店推荐或价格对比时触发。
     * 大模型需将用户提及的城市转为英文名称，并从上下文中提取入住和退房日期
     * （格式严格遵守 YYYY-MM-DD）。</p>
     *
     * @param city         目标城市英文名称，例如 "Paris"、"Rome"、"Amsterdam"
     * @param checkInDate  入住日期，格式 YYYY-MM-DD，例如 "2026-06-01"
     * @param checkOutDate 退房日期，格式 YYYY-MM-DD，例如 "2026-06-03"
     * @return 最多 3 条酒店信息列表，包含酒店名称、每晚价格（欧元）和综合评分；
     *         查询失败时返回空列表，不抛出异常
     */
    @Tool(description = "根据城市名称和入住/退房日期查询当地酒店的价格和评分信息。" +
            "城市名称需使用英文（如：Paris, Rome, Dublin）。" +
            "入住日期和退房日期格式均为 YYYY-MM-DD。" +
            "适用于用户询问目的地住宿价格、酒店推荐等场景。")
    public List<HotelDTO> searchHotels(String city, String checkInDate, String checkOutDate) {
        // Tools 层保持薄封装，参数校验、缓存和外部 API 异常由 PlacesService 负责。
        return placesService.searchHotels(city, checkInDate, checkOutDate);
    }

    /**
     * 查询指定城市的热门旅游景点列表。
     *
     * <p>此工具应在用户询问目的地游览、景点推荐、行程规划等场景时触发。
     * 大模型只需提供英文城市名称即可，无需额外日期参数。</p>
     *
     * @param city 目标城市英文名称，例如 "London"、"Barcelona"、"Prague"
     * @return 最多 3 个热门景点信息列表，包含景点名称、用户评分和景点类型分类；
     *         查询失败时返回空列表，不抛出异常
     */
    @Tool(description = "根据城市名称查询当地热门旅游景点信息，包含景点名称、评分和类型分类。" +
            "城市名称需使用英文（如：London, Barcelona, Prague）。" +
            "适用于用户询问目的地有哪些值得游览的景点、行程规划等场景。")
    public List<AttractionDTO> searchAttractions(String city) {
        // 景点查询不需要日期参数；返回数量和失败兜底统一由 Service 控制。
        return placesService.searchAttractions(city);
    }
}
