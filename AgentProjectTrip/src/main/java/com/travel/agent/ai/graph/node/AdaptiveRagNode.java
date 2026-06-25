package com.travel.agent.ai.graph.node;

import com.travel.agent.ai.graph.model.AdaptiveRagDecision;
import com.travel.agent.ai.graph.model.RagRetrievalResult;
import com.travel.agent.ai.graph.model.TravelPlanState;
import com.travel.agent.ai.rag.AdaptiveRagService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Adaptive RAG 节点（Graph 层 - 自适应知识检索）。
 *
 * <p>系统架构位置：LangGraphPlannerFacade -> <b>AdaptiveRagNode</b> -> AdaptiveRagService -> KnowledgeTools / Pinecone</p>
 *
 * <p>职责：
 * <ul>
 *   <li>读取 {@link TravelPlanState} 中的用户问题、结构化需求表、目的地、预算和偏好。</li>
 *   <li>调用 {@link AdaptiveRagService} 判断查询类型并执行对应检索策略。</li>
 *   <li>将 RAG 决策、检索结果、trace 摘要和最终上下文写回状态。</li>
 *   <li>Adaptive RAG 失败时回退旧 {@link RetrieveKnowledgeNode}，避免主规划流程中断。</li>
 * </ul>
 * </p>
 */
@Component
public class AdaptiveRagNode {

    private static final Logger log = LoggerFactory.getLogger(AdaptiveRagNode.class);

    /** 自适应检索编排服务，负责分类、策略选择和实际检索。 */
    private final AdaptiveRagService adaptiveRagService;

    /** 旧 RAG 节点 fallback；Adaptive RAG 失败时继续保持第 1-13 阶段可用行为。 */
    private final RetrieveKnowledgeNode retrieveKnowledgeNode;

    /**
     * 构造 Adaptive RAG 节点。
     *
     * @param adaptiveRagService  自适应检索服务
     * @param retrieveKnowledgeNode 旧 RAG fallback 节点
     */
    public AdaptiveRagNode(AdaptiveRagService adaptiveRagService, RetrieveKnowledgeNode retrieveKnowledgeNode) {
        this.adaptiveRagService = adaptiveRagService;
        this.retrieveKnowledgeNode = retrieveKnowledgeNode;
    }

    /**
     * 执行自适应 RAG 并写回 Graph 状态。
     *
     * <p>失败策略：如果分类、策略选择或 Pinecone 检索任一步抛出异常，
     * 节点会记录 fallback decision，然后调用旧 RetrieveKnowledgeNode。
     * 旧节点内部也有知识库异常兜底，因此外部 Facade 不需要因为 RAG 故障终止流程。</p>
     *
     * @param state 当前旅行规划状态
     * @return 写入 Adaptive RAG 上下文后的状态
     */
    public TravelPlanState retrieve(TravelPlanState state) {
        if (state == null) {
            return retrieveKnowledgeNode.retrieve(null);
        }

        try {
            RagRetrievalResult result = adaptiveRagService.retrieve(state);
            AdaptiveRagDecision decision = result.getDecision();

            state.setAdaptiveRagDecision(decision);
            state.setRagRetrievalResult(result);
            state.setRagContext(result.getContext());
            state.setRagTraceSummary(buildTraceSummary(result));

            log.info("[Graph][AdaptiveRAG] queryType={}, strategy={}, hits={}, queries={}",
                    decision == null ? null : decision.getQueryType(),
                    decision == null ? null : decision.getRetrievalStrategy(),
                    result.getHitCount(),
                    result.getExecutedQueries().size());
            return state;
        } catch (Exception e) {
            // Adaptive RAG 是增强能力，不是主流程硬依赖；失败后回退旧 RAG，保证用户仍能拿到方案。
            log.warn("[Graph][AdaptiveRAG] failed, fallback to RetrieveKnowledgeNode: {}", e.getMessage());
            state.setAdaptiveRagDecision(AdaptiveRagDecision.fallback(e.getMessage()));
            state.setRagTraceSummary("Adaptive RAG 失败，已回退旧 RetrieveKnowledgeNode：" + e.getMessage());
            return retrieveKnowledgeNode.retrieve(state);
        }
    }

    private static String buildTraceSummary(RagRetrievalResult result) {
        if (result == null || result.getDecision() == null) {
            return "Adaptive RAG 未产生可用 trace。";
        }
        AdaptiveRagDecision decision = result.getDecision();
        return "Adaptive RAG: type=" + decision.getQueryType()
                + ", strategy=" + decision.getRetrievalStrategy()
                + ", hits=" + result.getHitCount()
                + ", queries=" + result.getExecutedQueries().size();
    }
}
