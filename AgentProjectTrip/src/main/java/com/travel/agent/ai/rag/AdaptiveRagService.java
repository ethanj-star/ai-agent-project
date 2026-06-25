package com.travel.agent.ai.rag;

import com.travel.agent.ai.agents.RagQueryClassifierAgent;
import com.travel.agent.ai.graph.model.AdaptiveRagDecision;
import com.travel.agent.ai.graph.model.KnowledgeSourceType;
import com.travel.agent.ai.graph.model.RagQueryType;
import com.travel.agent.ai.graph.model.RagRetrievalResult;
import com.travel.agent.ai.graph.model.RagRetrievalStrategy;
import com.travel.agent.ai.graph.model.TravelPlanState;
import com.travel.agent.ai.graph.model.TravelRequirementSpec;
import com.travel.agent.ai.tools.KnowledgeTools;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Adaptive RAG 服务（AI 层 - 检索策略编排）。
 *
 * <p>系统架构位置：AdaptiveRagNode -> <b>AdaptiveRagService</b> -> KnowledgeTools -> Pinecone</p>
 *
 * <p>职责：
 * <ul>
 *   <li>调用 {@link RagQueryClassifierAgent} 判断本次查询类型。</li>
 *   <li>根据查询类型选择检索策略、topK、知识来源和改写后的检索 query。</li>
 *   <li>调用 {@link KnowledgeTools} 执行实际检索，并把结果压缩成 Planner 可读上下文。</li>
 * </ul>
 * </p>
 *
 * <p>第一版边界：这里先复用 Pinecone similaritySearch，通过 query 改写和多阶段检索体现 Adaptive；
 * TODO(stage14-media-crawler-rag-source)：MediaCrawler 内容入库后，需要加入 MySQL 景点主数据、
 * source profile、metadata filter 和 reranker。</p>
 */
@Service
public class AdaptiveRagService {

    /** RAG 分类 Agent，第一版使用规则分类，后续可接模型兜底。 */
    private final RagQueryClassifierAgent classifierAgent;

    /** 私有知识库工具，当前底层由 Spring AI VectorStore / Pinecone 实现。 */
    private final KnowledgeTools knowledgeTools;

    public AdaptiveRagService(RagQueryClassifierAgent classifierAgent, KnowledgeTools knowledgeTools) {
        this.classifierAgent = classifierAgent;
        this.knowledgeTools = knowledgeTools;
    }

    /**
     * 执行一次 Adaptive RAG 检索。
     *
     * <p>处理流程：</p>
     * <ol>
     *   <li>读取 TravelPlanState，判断查询类型。</li>
     *   <li>将查询类型映射为检索策略和 topK。</li>
     *   <li>根据策略构造一个或多个检索 query。</li>
     *   <li>调用 KnowledgeTools 检索 Pinecone，并聚合命中文档。</li>
     *   <li>返回决策和上下文，不直接修改 state，修改由 AdaptiveRagNode 统一负责。</li>
     * </ol>
     *
     * @param state 当前旅行规划状态
     * @return Adaptive RAG 检索结果
     */
    public RagRetrievalResult retrieve(TravelPlanState state) {
        AdaptiveRagDecision decision = classifierAgent.classify(state);
        enrichDecision(decision, state);

        List<Document> documents = new ArrayList<>();
        List<String> executedQueries = new ArrayList<>();
        for (String query : decision.getPlannedQueries()) {
            if (!hasText(query)) {
                continue;
            }
            executedQueries.add(query);
            documents.addAll(knowledgeTools.searchTravelGuideDocuments(query, decision.getTopK()));
        }

        RagRetrievalResult result = new RagRetrievalResult();
        result.setDecision(decision);
        result.setExecutedQueries(executedQueries);
        result.setHitCount(documents.size());
        result.setSourceSummaries(List.of("Pinecone private guide hits: " + documents.size()));
        result.setContext(buildContext(decision, documents));
        return result;
    }

    /**
     * 补全分类 Agent 没有负责的策略、来源、query 和 topK。
     */
    private static void enrichDecision(AdaptiveRagDecision decision, TravelPlanState state) {
        RagQueryType queryType = decision.getQueryType();
        decision.setRetrievalStrategy(strategyFor(queryType));
        decision.setTopK(topKFor(queryType));
        decision.setSourceTypes(sourceTypesFor(queryType));
        decision.setPlannedQueries(buildQueries(queryType, state));
    }

    private static RagRetrievalStrategy strategyFor(RagQueryType queryType) {
        return switch (queryType) {
            case EXPLORATORY -> RagRetrievalStrategy.SEMANTIC_EXPANSION;
            case COMPARATIVE -> RagRetrievalStrategy.COMPARATIVE_MULTI_SOURCE;
            case INSTRUCTIONAL -> RagRetrievalStrategy.STRUCTURED_GUIDE_FIRST;
            case MULTI_HOP -> RagRetrievalStrategy.MULTI_STAGE_CONTEXT;
            case FACT_BASED -> RagRetrievalStrategy.PRECISE_KEYWORD;
        };
    }

    private static int topKFor(RagQueryType queryType) {
        return switch (queryType) {
            case FACT_BASED -> 2;
            case INSTRUCTIONAL -> 3;
            case COMPARATIVE -> 2;
            case EXPLORATORY -> 4;
            case MULTI_HOP -> 3;
        };
    }

    private static List<KnowledgeSourceType> sourceTypesFor(RagQueryType queryType) {
        List<KnowledgeSourceType> sources = new ArrayList<>();
        sources.add(KnowledgeSourceType.REQUIREMENT_SPEC);
        sources.add(KnowledgeSourceType.PINECONE_PRIVATE_GUIDE);
        if (queryType == RagQueryType.INSTRUCTIONAL) {
            sources.add(KnowledgeSourceType.STRUCTURED_GUIDE);
        }
        if (queryType == RagQueryType.EXPLORATORY || queryType == RagQueryType.MULTI_HOP) {
            sources.add(KnowledgeSourceType.POI_CATALOG);
        }
        return sources;
    }

    /**
     * 根据查询类型构造检索 query。
     *
     * <p>第一版不做复杂 prompt 改写，而是用确定性模板增强召回。这样容易测试，
     * 也方便后续对每类 query 的命中效果做 Eval。</p>
     */
    private static List<String> buildQueries(RagQueryType queryType, TravelPlanState state) {
        String baseQuery = buildBaseQuery(state);
        List<String> destinations = destinations(state);
        return switch (queryType) {
            case FACT_BASED -> List.of(baseQuery + " 事实 信息 简介");
            case EXPLORATORY -> exploratoryQueries(baseQuery, destinations);
            case COMPARATIVE -> comparativeQueries(baseQuery, destinations);
            case INSTRUCTIONAL -> List.of(baseQuery + " 步骤 流程 攻略 注意事项 避坑");
            case MULTI_HOP -> multiHopQueries(baseQuery, destinations);
        };
    }

    private static List<String> exploratoryQueries(String baseQuery, List<String> destinations) {
        List<String> queries = new ArrayList<>();
        if (destinations.isEmpty()) {
            queries.add(baseQuery + " 小众 推荐 深度游 慢游 避开人多");
        } else {
            for (String destination : destinations) {
                queries.add(destination + " 小众 推荐 深度游 慢游 避开人多");
            }
        }
        queries.add(baseQuery + " 风格化旅行攻略 拍照 美食 亲子 徒步");
        return deduplicate(queries);
    }

    private static List<String> comparativeQueries(String baseQuery, List<String> destinations) {
        List<String> queries = new ArrayList<>();
        if (destinations.size() >= 2) {
            for (String destination : destinations) {
                queries.add(destination + " 优缺点 预算 交通 景点 人流 适合人群");
            }
            queries.add(String.join(" vs ", destinations) + " 对比 旅行 预算 交通");
        } else {
            queries.add(baseQuery + " 对比 优缺点 预算 交通 适合人群");
        }
        return deduplicate(queries);
    }

    private static List<String> multiHopQueries(String baseQuery, List<String> destinations) {
        List<String> queries = new ArrayList<>();
        if (destinations.isEmpty()) {
            queries.add(baseQuery + " 行程规划 路线 串联");
        } else {
            queries.add(String.join(" ", destinations) + " 行程规划 路线 串联 交通");
            for (String destination : destinations) {
                queries.add(destination + " 景点 推荐 交通 预算 避坑");
            }
        }
        queries.add(baseQuery + " 预算 住宿 交通 风险 人流");
        return deduplicate(queries);
    }

    private static String buildContext(AdaptiveRagDecision decision, List<Document> documents) {
        StringBuilder context = new StringBuilder();
        context.append("Adaptive RAG 检索决策：")
                .append(decision.getQueryType())
                .append(" / ")
                .append(decision.getRetrievalStrategy())
                .append("\n");
        if (hasText(decision.getReason())) {
            context.append("决策原因：").append(decision.getReason()).append("\n");
        }
        context.append("检索 query：").append(String.join(" | ", decision.getPlannedQueries())).append("\n\n");

        if (documents == null || documents.isEmpty()) {
            context.append("私有知识库中暂无相关攻略。");
            return context.toString();
        }

        context.append("检索到的参考攻略：\n");
        int index = 1;
        for (Document document : documents) {
            if (document == null || !hasText(document.getText())) {
                continue;
            }
            context.append("\n[").append(index++).append("] ")
                    .append(document.getText().trim());
        }
        return context.toString();
    }

    private static String buildBaseQuery(TravelPlanState state) {
        List<String> parts = new ArrayList<>();
        addIfPresent(parts, state == null ? null : state.getUserQuery());
        addAll(parts, destinations(state));
        if (state != null) {
            addIfPresent(parts, state.getTravelTime());
            addIfPresent(parts, state.getDurationText());
            addAll(parts, state.getKeywords());
        }

        TravelRequirementSpec spec = state == null ? null : state.getRequirementSpec();
        if (spec != null) {
            addIfPresent(parts, spec.getOriginalMessage());
            addIfPresent(parts, spec.getDepartureCity());
            addIfPresent(parts, spec.getStartDateText());
            addIfPresent(parts, spec.getTravelStyle());
            addIfPresent(parts, spec.getAccommodationPreference());
            addIfPresent(parts, spec.getTransportPreference());
            addAll(parts, spec.getPreferences());
            addAll(parts, spec.getAvoidances());
            if (spec.getBudgetAmount() != null) {
                addIfPresent(parts, "预算 " + spec.getBudgetAmount() + " " + spec.getBudgetCurrency());
            }
        }

        String result = String.join(" ", deduplicate(parts)).trim();
        return result.isEmpty() ? "欧洲旅行攻略 防坑 行程 交通 建议" : result;
    }

    private static List<String> destinations(TravelPlanState state) {
        if (state == null) {
            return List.of();
        }
        TravelRequirementSpec spec = state.getRequirementSpec();
        if (spec != null && spec.getDestinations() != null && !spec.getDestinations().isEmpty()) {
            return deduplicate(spec.getDestinations());
        }
        return deduplicate(state.getDestinations());
    }

    private static List<String> deduplicate(List<String> values) {
        Set<String> unique = new LinkedHashSet<>();
        if (values == null) {
            return new ArrayList<>();
        }
        for (String value : values) {
            if (hasText(value)) {
                unique.add(value.trim());
            }
        }
        return new ArrayList<>(unique);
    }

    private static void addAll(List<String> parts, List<String> values) {
        if (values == null) {
            return;
        }
        for (String value : values) {
            addIfPresent(parts, value);
        }
    }

    private static void addIfPresent(List<String> parts, String value) {
        if (hasText(value)) {
            parts.add(value.trim());
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
