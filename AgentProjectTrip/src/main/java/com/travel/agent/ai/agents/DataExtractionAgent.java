package com.travel.agent.ai.agents;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 小红书游记数据的AI驱动批量提取代理 (Agent)。
 * 该类作为 ETL 流程中的核心组件，利用大语言模型从非结构化文本中提取结构化旅行数据。
 *
 * <p>处理流程：
 * <ol>
 *   <li>读取数据：从 {@code data/processed/} 读取已清洗的 JSONL 文件。</li>
 *   <li>微批次分组：按 {@value BATCH_SIZE} 大小分组，防止单次请求 Token 超限。</li>
 *   <li>AI 提取：使用严格约束的 System Prompt 调用 LLM，强制输出标准 JSON。</li>
 *   <li>清洗与持久化：移除 Markdown 代码块标记，将纯净 JSON 写入 {@code data/extracted/}。</li>
 * </ol>
 */
@Service
public class DataExtractionAgent {

    private static final Logger log = LoggerFactory.getLogger(DataExtractionAgent.class);

    private static final String PROCESSED_DIR = System.getProperty("user.dir") + "/data/processed/";
    private static final String EXTRACTED_DIR = System.getProperty("user.dir") + "/data/extracted/";

    /** 单次 Prompt 发送给 LLM 的 JSONL 记录数。设为 1 逐条处理，换取最高准确率。 */
    private static final int BATCH_SIZE = 1;

    private static final String EXTRACT_SYSTEM_PROMPT =
            "你是一个极其严谨的欧洲旅行数据萃取专家。我会给你一批小红书游记的 JSON 数据，请深度阅读并从中提取具体的旅行实体，严格输出为一个包含对象的 JSON 数组。\n\n" +
            "【核心规则】\n" +
            "1. 范围限制：仅提取涉及【法国】、【意大利】、【瑞士】的内容，其他国家/地区的信息一律直接丢弃。\n" +
            "2. 严禁捏造：绝对不允许凭空捏造。剔除所有 Emoji。如果原文缺失某项信息，对应的 JSON 字段必须严格输出为 null，不要自己编造默认值。\n" +
            "3. 输出格式：只返回合法的 JSON Array，禁止输出任何 Markdown 标记（如 ```json ），禁止包含任何多余的解释文字。\n\n" +
            "【提取实体与 JSON 字段规范】\n" +
            "请将提取的信息分为以下三种实体类型（type），并严格使用对应的英文字段名：\n\n" +
            "类型 A: ROUTE (宏观多国路线)\n" +
            "  - type: \"ROUTE\"\n" +
            "  - countries: 涉及的国家（数组）\n" +
            "  - total_days: 总天数（数字或字符串，如原文未提则为 null）\n" +
            "  - core_style: 核心风格（如特种兵、深度游、亲子等）\n\n" +
            "类型 B: POI (具体地点：景点/餐厅/交通枢纽)\n" +
            "  - type: \"POI\"\n" +
            "  - country: 国家\n" +
            "  - city: 城市\n" +
            "  - name: 地点/餐厅名称\n" +
            "  - suggested_duration: 建议游玩时长\n" +
            "  - booking_rules: 价格、门票或预订提示（如果没有则为 null）\n" +
            "  - highlights: 亮点描述\n\n" +
            "类型 C: TIP (避坑与高价值经验)\n" +
            "  - type: \"TIP\"\n" +
            "  - country: 适用的国家（如果通用则为 null）\n" +
            "  - city: 适用的城市（如果通用则为 null）\n" +
            "  - content: 具体的经验内容\n\n" +
            "【警告】如果提取不到符合法意瑞的实体，请直接输出空的方括号 []。绝对不允许无意义的字符重复！输出完毕后立刻停止生成！";

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;

    public DataExtractionAgent(ChatClient.Builder builder, ObjectMapper objectMapper) {
        this.chatClient   = builder.build();
        this.objectMapper = objectMapper;
    }

    // -------------------------------------------------------------------------
    // 公开 API
    // -------------------------------------------------------------------------

    /**
     * 完整的提取流程：从 processed 目录读取输入文件，运行 LLM 批量提取，
     * 将结构化 JSON 数组写入 extracted 目录下的输出文件。
     *
     * <p>设计为在后台线程中调用——它是阻塞的，根据文件大小和模型延迟可能需要几分钟。
     *
     * @param inputFileName  {@code data/processed/} 下的纯文件名
     * @param outputFileName {@code data/extracted/} 下要创建的纯文件名
     */
    public void runExtractionTask(String inputFileName, String outputFileName) {
        // Controller 只传文件名；这里统一拼成 ETL 约定目录，避免 Web 层知道本地文件结构。
        String inputPath  = PROCESSED_DIR + inputFileName;
        String outputPath = EXTRACTED_DIR + outputFileName;

        try {
            // 输出目录可能是首次运行才创建。目录创建失败时直接结束，避免后面写文件再抛更难懂的异常。
            Files.createDirectories(Paths.get(EXTRACTED_DIR));
        } catch (IOException e) {
            log.error("[Extraction] Cannot create output directory [{}]: {}", EXTRACTED_DIR, e.getMessage());
            return;
        }

        log.info("[Extraction] Task started: {} -> {}", inputPath, outputPath);
        int totalLines   = 0;
        int totalBatches = 0;

        try (BufferedReader reader = new BufferedReader(
                     new InputStreamReader(new FileInputStream(inputPath), StandardCharsets.UTF_8));
             BufferedWriter writer = new BufferedWriter(
                     new OutputStreamWriter(new FileOutputStream(outputPath), StandardCharsets.UTF_8))) {

            List<String> batch = new ArrayList<>(BATCH_SIZE);
            String rawLine;

            while ((rawLine = reader.readLine()) != null) {
                rawLine = rawLine.trim();
                // 清洗阶段可能留下空行；空行不是一条有效 JSONL 记录，直接跳过。
                if (rawLine.isEmpty()) {
                    continue;
                }
                totalLines++;
                batch.add(rawLine);

                if (batch.size() == BATCH_SIZE) {
                    // BATCH_SIZE 当前为 1，等于逐条调用模型；这样慢一些，但对抽取准确率和定位坏数据更友好。
                    processBatch(batch, writer, ++totalBatches);
                    batch.clear();
                }
            }

            if (!batch.isEmpty()) {
                // 文件末尾不足一个批次时，也要把剩余记录送给模型处理。
                processBatch(batch, writer, ++totalBatches);
            }

        } catch (IOException e) {
            // 输入文件不存在、读取失败、写入失败都属于任务级 I/O 错误，当前批处理无法继续。
            log.error("[Extraction] Fatal I/O error during extraction task", e);
            return;
        }

        log.info("[Extraction] Done — {} records processed in {} LLM batches -> {}",
                totalLines, totalBatches, outputPath);
    }

    // -------------------------------------------------------------------------
    // 私有辅助方法
    // -------------------------------------------------------------------------

    /**
     * 将一个批次发送给 LLM，解析响应为 JSONL 格式后逐行落盘。
     *
     * <p>核心流程：
     * <ol>
     *   <li>将批次数据序列化为 JSON Array 字符串，作为 user message 传给大模型。</li>
     *   <li>调用大模型，获取原始响应字符串。</li>
     *   <li>清理 Markdown 代码块标记，拿到纯净 JSON。</li>
     *   <li><b>扁平化写入 (JSONL Flatten)</b>：将 JSON Array 解析为节点树，
     *       逐个提取其中的 JSON Object，每个单独序列化为一行写入文件，
     *       实现标准 JSONL（每行一个独立 JSON 对象）格式。</li>
     *   <li>每条写入后立即 flush，确保数据实时落盘，调试断点时文件内容也是最新的。</li>
     * </ol>
     *
     * <p>异常策略：最外层 catch 捕获网络/模型级别的异常；内层 try-catch 独立处理
     * JSON 解析失败，两者互不干扰，单批失败不会中止整个任务。
     *
     * @param batch       当前批次的原始 JSONL 行列表
     * @param writer      输出文件的 BufferedWriter（由调用方通过 try-with-resources 管理生命周期）
     * @param batchNumber 批次编号，仅用于日志追踪
     */
    private void processBatch(List<String> batch, BufferedWriter writer, int batchNumber) {
        try {
            // 将 List<String>（每个元素是一行 JSONL 文本）序列化为 JSON Array 字符串，
            // 例如：["{"note_id":"abc","title":"..."}"] ，作为 user message 送给大模型
            String batchJson = objectMapper.writeValueAsString(batch);

            log.info("[Extraction] Calling LLM for batch #{} ({} records)...", batchNumber, batch.size());

            // Step 1：调用大模型执行结构化抽取。
            String rawResponse = chatClient.prompt()
                    .system(EXTRACT_SYSTEM_PROMPT)
                    .user(batchJson)
                    // 护栏参数：锁定模型、压低温度、硬性截断 Token 上限，防止无限循环幻觉
                    .options(OpenAiChatOptions.builder()
                            .model("qwen-plus")
                            .temperature(0.01)
                            .maxTokens(1500)
                            .build())
                    .call()
                    .content();

            // Step 2：清理 Markdown 代码块标记。
            // 大模型经常无视"禁止 Markdown"指令，在 JSON 外面包裹 ```json ... ```
            // stripMarkdownFences 负责剥离这层包装，确保 cleaned 是纯净的 JSON 字符串
            String cleaned = stripMarkdownFences(rawResponse);

            // Step 3：强解析 + 扁平化写入（JSON Array -> JSONL）。
            // 问题根源：大模型返回的是 "[{...}, {...}]" 这样的 JSON Array。
            // 如果直接 writer.write(cleaned)，文件里会出现 "[...] [...]" 的非法拼接格式。
            // 正确做法：将 Array 解析为节点树，提取每个子节点，逐行写入，生成标准 JSONL。
            try {
                JsonNode rootNode = objectMapper.readTree(cleaned);

                if (rootNode.isArray()) {
                    // 正常路径：大模型按要求返回了 Array，逐个元素扁平化写入。
                    int writtenCount = 0;
                    for (JsonNode entityNode : rootNode) {
                        // 将每个 JSON Object 节点序列化为单行字符串，不含换行
                        String line = objectMapper.writeValueAsString(entityNode);
                        writer.write(line);
                        // 每条记录独占一行，符合 JSONL 规范
                        writer.newLine();
                        // 每写一行就 flush，保证即使程序中途崩溃，已处理的数据也在硬盘上。
                        writer.flush();
                        writtenCount++;
                    }
                    log.info("[Extraction] Batch #{} → {} entities written to JSONL.", batchNumber, writtenCount);
                } else {
                    // 降级路径：大模型返回的不是 Array（可能是单个 Object 或纯文本说明）。
                    // 安全兜底：将整个响应作为一行原样写入，不丢数据，方便人工 review
                    log.warn("[Extraction] Batch #{} response is not a JSON Array (type={}), writing raw line.",
                            batchNumber, rootNode.getNodeType());
                    writer.write(cleaned);
                    writer.newLine();
                    writer.flush();
                }

            } catch (Exception parseEx) {
                // JSON 解析失败（大模型返回了不合法的 JSON）
                // 记录原始响应的前 200 字符，方便人工排查是乱码还是模型抽风
                String rawSnippet = cleaned.substring(0, Math.min(200, cleaned.length()));
                log.error("[Extraction] Batch #{} JSON parse failed — raw response snippet: {} | Cause: {}",
                        batchNumber, rawSnippet, parseEx.getMessage());
            }

        } catch (Exception e) {
            // 捕获网络超时、模型 API 报错等上游异常
            // 同时打印首条原始帖子数据的摘要，追溯是哪篇内容导致模型崩溃
            String batchSnippet = batch.isEmpty() ? "(empty)"
                    : batch.get(0).substring(0, Math.min(120, batch.get(0).length())) + "...";
            log.error("[Extraction] Batch #{} LLM call failed — skipping. Cause: {} | First record snippet: {}",
                    batchNumber, e.getMessage(), batchSnippet);
        }
    }

    /**
     * 移除 LLM 有时用于包裹 JSON 的 Markdown 代码块标记。
     *
     * <p>处理所有常见变体：{@code ```json\n...\n```} 与 {@code ```\n...\n```}。
     *
     * @param raw LLM 返回的原始字符串
     * @return 清理后的 JSON 字符串，或在内容为空时返回 {@code "[]"}
     */
    private static String stripMarkdownFences(String raw) {
        if (raw == null || raw.isBlank()) {
            return "[]";
        }
        String s = raw.strip();

        if (s.startsWith("```")) {
            int newline = s.indexOf('\n');
            s = (newline != -1) ? s.substring(newline + 1).strip() : s.substring(3).strip();
        }
        if (s.endsWith("```")) {
            s = s.substring(0, s.length() - 3).strip();
        }

        return s.isBlank() ? "[]" : s;
    }
}
