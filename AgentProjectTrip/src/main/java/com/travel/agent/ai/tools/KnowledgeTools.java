package com.travel.agent.ai.tools;

import org.springframework.ai.document.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;



/**
 * 私有知识库检索工具（AI 层 - 工具桥接器）
 *
 * <p>系统架构位置：Agent 层 → <b>Tools 层</b> → VectorStore（Pinecone）</p>
 *
 * <p>职责：将 Pinecone 向量数据库中的旅游攻略私有知识暴露给大模型。
 * 当大模型判断用户问题属于经验性、攻略性的查询时，框架将自动触发
 * {@link #searchTravelGuide} 方法，以语义相似度方式检索最匹配的文档片段，
 * 再将原文作为上下文注入大模型的推理链，实现 RAG（检索增强生成）。</p>
 */
@Component
public class KnowledgeTools {

    private final VectorStore vectorStore;

    /**
     * 构造私有知识库工具。
     *
     * @param vectorStore Spring AI VectorStore，当前由 Pinecone 实现
     */
    public KnowledgeTools(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    /**
     * 从私有知识库中语义检索与用户查询最相关的旅游攻略。
     *
     * @param query 用户的自然语言查询，例如："冰岛看极光最佳时间" 或 "巴黎如何防诈骗"
     * @return 检索到的原文攻略拼接字符串；若知识库中无匹配内容则返回提示语
     */
    @Tool(description = "当用户询问关于旅游目的地的攻略、防坑指南、交通建议、最佳季节等经验性问题时，" +
            "必须调用此工具从私有知识库中检索相关经验。")
    public String searchTravelGuide(String query) {
        // Tool Calling 场景仍使用保守 topK=2，避免模型自动工具调用时塞入过长上下文。
        return searchTravelGuide(query, 2);
    }

    /**
     * 从私有知识库中检索指定数量的旅游攻略文本。
     *
     * <p>系统内部 Adaptive RAG 会根据查询类型动态选择 topK。该方法不加 {@link Tool} 注解，
     * 因为它是后端节点编排使用的确定性能力，不直接暴露给大模型自由调用。</p>
     *
     * @param query 用户查询或 Adaptive RAG 改写后的检索 query
     * @param topK  最多召回的文档数量，内部会限制在 1-8 之间
     * @return 检索到的原文攻略拼接字符串；若知识库中无匹配内容则返回提示语
     */
    public String searchTravelGuide(String query, int topK) {
        List<Document> results = searchTravelGuideDocuments(query, topK);
        return formatTravelGuideResults(results);
    }

    /**
     * 返回原始 Document 列表，供 Adaptive RAG 记录命中文档数量和后续 metadata trace。
     *
     * <p>TODO(stage14-adaptive-rag-metadata-filter)：后续接入景点主数据和 MediaCrawler metadata 后，
     * 这里应支持 country、city、poi、styleTags、sourceType 等过滤条件，而不是只靠语义相似度。</p>
     *
     * @param query 用户查询或改写后的检索 query
     * @param topK  最多召回的文档数量，内部会限制在 1-8 之间
     * @return VectorStore 返回的 Document 列表；无命中时为空列表
     */
    public List<Document> searchTravelGuideDocuments(String query, int topK) {
        int safeTopK = Math.min(8, Math.max(1, topK));
        return vectorStore.similaritySearch(
                SearchRequest.builder().query(query).topK(safeTopK).build()
        );
    }

    /**
     * 将 VectorStore 文档列表转成 Planner 可读的 RAG 上下文。
     */
    public String formatTravelGuideResults(List<Document> results) {
        if (results == null || results.isEmpty()) {
            // 返回可读提示而不是空字符串，调用方可以明确知道是“无命中”而不是工具没执行。
            return "私有知识库中暂无相关攻略。";
        }

        String combined = results.stream()
                // Spring AI 新版本通过 getText() 读取文档正文。
                .map(Document::getText)
                .collect(Collectors.joining("\n\n"));

        return "检索到的参考攻略：\n" + combined;
    }
}
