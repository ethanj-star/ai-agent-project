package com.travel.agent.ai.graph.node;

import com.travel.agent.ai.graph.model.TravelPlanState;
import com.travel.agent.ai.tools.KnowledgeTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 私有知识库检索节点（Graph 层 - RAG 上下文注入）。
 *
 * <p>系统架构位置：LangGraphPlannerFacade → <b>RetrieveKnowledgeNode</b> → KnowledgeTools → Pinecone</p>
 *
 * <p>职责：
 * <ul>
 *   <li>根据用户原始输入、目的地、出行时间、行程时长和关键词构造语义检索 query。</li>
 *   <li>调用 {@link KnowledgeTools} 从 Pinecone 私有知识库检索攻略、防坑和 POI 上下文。</li>
 *   <li>将检索结果写回 {@link TravelPlanState#setRagContext(String)}，供 Planner 节点使用。</li>
 *   <li>知识库不可用时写入兜底上下文，不中断整条规划流程。</li>
 * </ul>
 * </p>
 */
@Component
public class RetrieveKnowledgeNode {

    private static final Logger log = LoggerFactory.getLogger(RetrieveKnowledgeNode.class);

    /** RAG 检索失败时写入 state 的兜底上下文 */
    private static final String FALLBACK_CONTEXT =
            "私有知识库暂时不可用，本次规划将主要基于通用旅行知识生成。";

    /** Spring AI 工具桥接器，内部通过 VectorStore 查询 Pinecone */
    private final KnowledgeTools knowledgeTools;

    /**
     * 构造器注入知识库工具。
     *
     * @param knowledgeTools 私有知识库检索工具，负责实际的 VectorStore similaritySearch
     */
    public RetrieveKnowledgeNode(KnowledgeTools knowledgeTools) {
        this.knowledgeTools = knowledgeTools;
    }

    /**
     * 执行 RAG 检索并更新状态。
     *
     * <p>异常策略：KnowledgeTools 或底层 Pinecone 抛出异常时，不向上传播；
     * 节点会写入 {@link #FALLBACK_CONTEXT}，让 Planner 仍可生成一版通用规划。</p>
     *
     * @param state 当前旅行规划状态
     * @return 写入 ragContext 后的状态
     */
    public TravelPlanState retrieve(TravelPlanState state) {
        // RAG 节点失败不应阻塞规划；state 为空时写兜底上下文即可。
        if (state == null) {
            TravelPlanState fallback = new TravelPlanState();
            fallback.setRagContext(FALLBACK_CONTEXT);
            return fallback;
        }

        String query = buildQuery(state);
        log.info("[Graph][RetrieveKnowledge] query={}", query);

        try {
            // KnowledgeTools 内部会访问向量库；这里把异常包住，避免外部知识库故障打断 Graph。
            String context = knowledgeTools.searchTravelGuide(query);
            // 工具正常返回但没有命中内容时，也写入显式提示，便于 Validator 识别 RAG 不足
            if (!hasText(context)) {
                context = "私有知识库中暂无相关攻略。";
            }
            state.setRagContext(context);
            log.info("[Graph][RetrieveKnowledge] contextLength={}", context.length());
        } catch (Exception e) {
            log.warn("[Graph][RetrieveKnowledge] retrieval failed: {}", e.getMessage());
            state.setRagContext(FALLBACK_CONTEXT);
        }

        return state;
    }

    /**
     * 根据当前状态构造 Pinecone 语义检索 query。
     *
     * <p>拼接策略：优先保留用户原始问题，再补充 Gatekeeper 提取出的目的地、出行时间、行程时长和偏好。
     * 这样既保留自然语言语义，也给向量检索提供明确实体。</p>
     *
     * @param state 当前旅行规划状态
     * @return 非空检索 query
     */
    String buildQuery(TravelPlanState state) {
        StringBuilder query = new StringBuilder();

        // 用户原文通常包含最完整语义，先放原文，再追加结构化实体增强召回。
        if (hasText(state.getUserQuery())) {
            query.append(state.getUserQuery()).append(' ');
        }
        if (state.getDestinations() != null && !state.getDestinations().isEmpty()) {
            query.append("目的地: ").append(String.join("、", state.getDestinations())).append(' ');
        }
        if (hasKnownTravelTime(state.getTravelTime())) {
            query.append("时间: ").append(state.getTravelTime()).append(' ');
        }
        if (hasText(state.getDurationText())) {
            query.append("时长: ").append(state.getDurationText()).append(' ');
        }
        if (state.getKeywords() != null && !state.getKeywords().isEmpty()) {
            query.append("偏好: ").append(String.join("、", state.getKeywords()));
        }

        String result = query.toString().trim();
        // 极端情况下没有任何状态字段，仍返回一个通用 query，保证向量检索参数非空。
        return result.isEmpty() ? "欧洲旅行攻略 防坑 行程 交通 建议" : result;
    }

    /**
     * 判断字符串是否包含非空白文本。
     */
    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static boolean hasKnownTravelTime(String value) {
        return hasText(value) && !"未指定".equals(value.trim());
    }
}
