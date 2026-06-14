package com.travel.agent.core.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库向量化灌入服务（核心业务层）
 *
 * <p>职责：将 AI ETL 阶段生成的结构化旅游实体 JSONL 文件，逐行解析、拼接为语义
 * 丰富的自然语言文本，携带结构化 Metadata，分批调用 {@link VectorStore#add} 进行
 * Embedding 计算与 Pinecone 持久化。
 *
 * <p>设计要点：
 * <ul>
 *   <li>流式 {@link BufferedReader} 读取，内存占用与文件大小无关。</li>
 *   <li>每 {@value BATCH_SIZE} 条 Document 触发一次 {@code vectorStore.add}，
 *       避免单次请求超过 Pinecone / Embedding API 的 body 限制。</li>
 *   <li>每行解析异常仅跳过该行，不中断整个灌入任务。</li>
 *   <li>null 字段在文本拼接时自动忽略，不会产生 "null" 字面量污染语义向量。</li>
 * </ul>
 */
@Service
public class KnowledgeBaseService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseService.class);

    /** 每批向 Pinecone 提交的最大 Document 数量。 */
    private static final int BATCH_SIZE = 20;

    /**
     * 提取完成的 JSONL 文件所在目录，相对于项目工作根目录。
     * 与 DataExtractionAgent 的 EXTRACTED_DIR 保持一致。
     */
    private static final String EXTRACTED_DIR =
            System.getProperty("user.dir") + "/data/extracted/";

    private final VectorStore vectorStore;
    private final ObjectMapper objectMapper;

    public KnowledgeBaseService(VectorStore vectorStore, ObjectMapper objectMapper) {
        this.vectorStore  = vectorStore;
        this.objectMapper = objectMapper;
    }

    // -------------------------------------------------------------------------
    // 公开 API
    // -------------------------------------------------------------------------

    /**
     * 从 {@code data/extracted/} 目录读取指定的实体 JSONL 文件，
     * 将每条实体记录转换为语义文本 + Metadata，分批灌入 Pinecone。
     *
     * <p>支持的实体类型（type 字段）：ROUTE / POI / TIP。
     * 未知类型的记录同样会被灌入，但文本拼接将只包含其实际非空字段。
     *
     * @param inputFileName 文件名，例如 {@code "france_italy_entities.jsonl"}
     */
    public void ingestRealKnowledge(String inputFileName) {
        // Controller 只传文件名，Service 在这里统一拼成 data/extracted 下的实际路径。
        String filePath = EXTRACTED_DIR + inputFileName;
        log.info("[Ingestion] Starting vector ingestion: {}", filePath);

        // 三个计数器分别用于最终日志：总读取、成功灌入、被跳过。
        int totalLines   = 0;
        int ingestedDocs = 0;
        int skippedLines = 0;

        // 批量提交可以减少 Embedding/向量库请求次数，也避免单次 body 过大。
        List<Document> batch = new ArrayList<>(BATCH_SIZE);

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(filePath), StandardCharsets.UTF_8))) {

            String rawLine;
            while ((rawLine = reader.readLine()) != null) {
                rawLine = rawLine.trim();
                if (rawLine.isEmpty()) {
                    continue;
                }
                totalLines++;

                try {
                    // JSONL 是一行一个 JSON 对象，逐行解析可以处理很大的文件。
                    JsonNode node = objectMapper.readTree(rawLine);

                    // 跳过解析后不是 JSON Object 的行（如空数组 [] 占位行）
                    if (!node.isObject()) {
                        log.debug("[Ingestion] Line #{} is not a JSON Object, skipping.", totalLines);
                        skippedLines++;
                        continue;
                    }

                    String content  = buildContent(node);
                    Map<String, Object> metadata = buildMetadata(node);

                    // content 为空意味着该记录无任何有效字段，灌入无意义，直接跳过
                    if (content.isBlank()) {
                        log.warn("[Ingestion] Line #{} produced empty content, skipping.", totalLines);
                        skippedLines++;
                        continue;
                    }

                    batch.add(new Document(content, metadata));

                    // 达到批次上限，立即提交
                    if (batch.size() == BATCH_SIZE) {
                        flushBatch(batch, ingestedDocs);
                        ingestedDocs += batch.size();
                        batch.clear();
                    }

                } catch (Exception lineEx) {
                    // 单行坏数据不应该让整个知识库灌入失败，所以只记录并继续下一行。
                    log.warn("[Ingestion] Failed to process line #{}: {}", totalLines, lineEx.getMessage());
                    skippedLines++;
                }
            }

            // 提交最后一批不足 BATCH_SIZE 的剩余记录
            if (!batch.isEmpty()) {
                flushBatch(batch, ingestedDocs);
                ingestedDocs += batch.size();
                batch.clear();
            }

        } catch (IOException e) {
            log.error("[Ingestion] Fatal I/O error while reading [{}]: {}", filePath, e.getMessage());
            return;
        }

        log.info("[Ingestion] Done — total={} | ingested={} | skipped={} | file={}",
                totalLines, ingestedDocs, skippedLines, filePath);
    }

    // -------------------------------------------------------------------------
    // 私有辅助方法
    // -------------------------------------------------------------------------

    /**
     * 将 JSON 实体节点拼接为一段语义丰富的自然语言字符串，用于 Embedding 计算。
     *
     * <p>拼接策略：只追加非 null、非空白的字段，彻底避免 "null" 字面量
     * 污染向量空间。字段顺序从宏观到具体，帮助 Embedding 模型捕获完整语义。
     *
     * @param node 单条实体的 JSON 节点
     * @return 拼接好的自然语言文本，可能为空字符串（调用方负责过滤）
     */
    private static String buildContent(JsonNode node) {
        // StringBuilder 按固定字段顺序拼接，最终文本越稳定，向量检索表现越可预期。
        StringBuilder sb = new StringBuilder();

        appendField(sb, "类型",     node, "type");
        appendField(sb, "国家",     node, "country");
        appendField(sb, "城市",     node, "city");
        appendField(sb, "名称",     node, "name");
        appendField(sb, "核心风格", node, "core_style");
        appendField(sb, "总天数",   node, "total_days");
        appendField(sb, "亮点",     node, "highlights");
        appendField(sb, "建议游玩时长", node, "suggested_duration");
        appendField(sb, "预订提示", node, "booking_rules");
        appendField(sb, "经验建议", node, "content");

        // 处理 countries 数组字段（ROUTE 类型专有）
        JsonNode countriesNode = node.path("countries");
        if (countriesNode.isArray() && !countriesNode.isEmpty()) {
            sb.append("涉及国家：");
            List<String> names = new ArrayList<>();
            countriesNode.forEach(c -> {
                String v = c.asText("").trim();
                if (!v.isEmpty()) names.add(v);
            });
            if (!names.isEmpty()) {
                sb.append(String.join("、", names)).append("。");
            }
        }

        return sb.toString().trim();
    }

    /**
     * 将重要的结构化字段提取为 Metadata Map，供 Pinecone FilterExpression 过滤使用。
     *
     * <p>只写入有实际值的字段，null 字段不写入 Map（Pinecone 对 null value 不友好）。
     *
     * @param node 单条实体的 JSON 节点
     * @return 可直接传入 {@link Document} 构造函数的 Metadata Map
     */
    private static Map<String, Object> buildMetadata(JsonNode node) {
        // Metadata 用于过滤，不追求长文本完整性，只保留最常用的检索维度。
        Map<String, Object> meta = new HashMap<>();
        putIfPresent(meta, "type",    node, "type");
        putIfPresent(meta, "country", node, "country");
        putIfPresent(meta, "city",    node, "city");
        putIfPresent(meta, "name",    node, "name");
        return meta;
    }

    /**
     * 向 VectorStore 提交一个批次，并打印进度日志。
     *
     * @param batch         当前待提交的 Document 列表（非空）
     * @param alreadyDone   本次调用前已累计灌入的文档数，用于日志显示
     */
    private void flushBatch(List<Document> batch, int alreadyDone) {
        log.info("[Ingestion] Flushing batch of {} docs (total so far: {})...",
                batch.size(), alreadyDone + batch.size());
        // VectorStore.add 会在内部触发 Embedding 并写入 Pinecone。
        vectorStore.add(batch);
        log.info("[Ingestion] Batch committed to Pinecone successfully.");
    }

    /**
     * 如果 JSON 节点中的指定字段有非空文本值，则以 "label：value。" 格式追加到 StringBuilder。
     */
    private static void appendField(StringBuilder sb, String label, JsonNode node, String field) {
        JsonNode val = node.path(field);
        if (!val.isNull() && !val.isMissingNode()) {
            String text = val.asText("").trim();
            // ETL 文件里有时会出现字符串 "null"，这里也当作空值处理。
            if (!text.isEmpty() && !text.equals("null")) {
                sb.append(label).append("：").append(text).append("。");
            }
        }
    }

    /**
     * 如果 JSON 节点中的指定字段有非空文本值，则写入 Metadata Map。
     */
    private static void putIfPresent(Map<String, Object> meta, String key,
                                     JsonNode node, String field) {
        JsonNode val = node.path(field);
        if (!val.isNull() && !val.isMissingNode()) {
            String text = val.asText("").trim();
            // Pinecone metadata 不适合写入空值或字符串 "null"。
            if (!text.isEmpty() && !text.equals("null")) {
                meta.put(key, text);
            }
        }
    }
}
