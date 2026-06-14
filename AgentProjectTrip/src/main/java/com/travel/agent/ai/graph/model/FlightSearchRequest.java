package com.travel.agent.ai.graph.model;

/**
 * 航班分支的工具查询请求（Graph 层 - 分支工具参数）。
 *
 * <p>系统架构位置：BranchTask -> <b>FlightSearchRequest</b> -> BranchAgentFacade -> FlightTools</p>
 *
 * <p>职责：
 * <ul>
 *   <li>保存调用航班工具所需的 IATA 出发机场、到达机场和出发日期。</li>
 *   <li>在参数不足时保存明确失败原因，让分支返回可解释的 {@link BranchResult}。</li>
 *   <li>把“能不能真实查询”的判断集中起来，避免 Planner 或工具层被迫猜测。</li>
 * </ul>
 * </p>
 *
 * @param originCode      出发地 IATA 三字码
 * @param destinationCode 目的地 IATA 三字码
 * @param departureDate   出发日期，格式 YYYY-MM-DD
 * @param sourceDescription 面向日志和摘要的人类可读参数来源
 * @param missingReason   参数不足或无法解析时的原因
 * @param queryable       为 true 时才允许调用真实航班工具
 */
public record FlightSearchRequest(
        String originCode,
        String destinationCode,
        String departureDate,
        String sourceDescription,
        String missingReason,
        boolean queryable
) {

    public static FlightSearchRequest ready(String originCode,
                                            String destinationCode,
                                            String departureDate,
                                            String sourceDescription) {
        return new FlightSearchRequest(originCode, destinationCode, departureDate, sourceDescription, null, true);
    }

    public static FlightSearchRequest missing(String missingReason) {
        return new FlightSearchRequest(null, null, null, null, missingReason, false);
    }
}
