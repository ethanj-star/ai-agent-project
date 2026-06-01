package com.travel.agent.ai.dto;

import com.travel.agent.ai.graph.model.TravelRequirementSpec;

/**
 * 需求表确认请求 DTO。
 *
 * <p>系统架构位置：前端表单 -> <b>RequirementConfirmRequest</b> -> RequirementController</p>
 *
 * <p>职责：
 * <ul>
 *   <li>允许前端在确认时携带用户最后修改后的需求表。</li>
 *   <li>如果请求体为空，Controller 会使用仓库中已保存的需求表做确认。</li>
 * </ul>
 * </p>
 */
public class RequirementConfirmRequest {

    /** 用户确认前最后一次编辑后的需求表，可为空。 */
    private TravelRequirementSpec spec;

    public TravelRequirementSpec getSpec() {
        return spec;
    }

    public void setSpec(TravelRequirementSpec spec) {
        this.spec = spec;
    }
}
