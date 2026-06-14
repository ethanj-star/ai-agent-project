package com.travel.agent.ai.graph.model;

/**
 * 酒店分支的工具查询请求（Graph 层 - 分支工具参数）。
 *
 * <p>系统架构位置：BranchTask -> <b>HotelSearchRequest</b> -> BranchAgentFacade -> PlacesTools</p>
 *
 * <p>职责：
 * <ul>
 *   <li>保存调用酒店工具所需的英文城市名、入住日期和退房日期。</li>
 *   <li>在日期、天数或城市无法解析时保存失败原因，避免分支伪造酒店价格。</li>
 *   <li>作为 HOTEL 分支和 PlacesTools.searchHotels(...) 之间的稳定参数协议。</li>
 * </ul>
 * </p>
 *
 * @param city              酒店查询城市英文名
 * @param checkInDate       入住日期，格式 YYYY-MM-DD
 * @param checkOutDate      退房日期，格式 YYYY-MM-DD
 * @param sourceDescription 面向日志和摘要的人类可读参数来源
 * @param missingReason     参数不足或无法解析时的原因
 * @param queryable         为 true 时才允许调用真实酒店工具
 */
public record HotelSearchRequest(
        String city,
        String checkInDate,
        String checkOutDate,
        String sourceDescription,
        String missingReason,
        boolean queryable
) {

    public static HotelSearchRequest ready(String city,
                                           String checkInDate,
                                           String checkOutDate,
                                           String sourceDescription) {
        return new HotelSearchRequest(city, checkInDate, checkOutDate, sourceDescription, null, true);
    }

    public static HotelSearchRequest missing(String missingReason) {
        return new HotelSearchRequest(null, null, null, null, missingReason, false);
    }
}
