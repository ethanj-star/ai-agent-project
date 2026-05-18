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
        List<Document> results = vectorStore.similaritySearch(
                SearchRequest.builder().query(query).topK(2).build()
        );

        if (results == null || results.isEmpty()) {
            return "私有知识库中暂无相关攻略。";
        }

        String combined = results.stream()
                .map(Document::getText)    //  使用最新版本的正确 API
                .collect(Collectors.joining("\n\n"));

        return "检索到的参考攻略：\n" + combined;
    }
}
