package com.travel.agent.core.etl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

/**
 * ── 高性能流式 ETL 清洗器（专用于小红书 JSONL 导出数据）─────────────────────────────────
 *
 * <p>核心设计：通过 {@link BufferedReader} 逐行读取，并使用 {@link BufferedWriter} 逐行写入。
 * 这样可以保证极低的、恒定的内存占用（O(1) 空间复杂度），无论处理几个 GB 还是几十 GB 的文件都不会 OOM（内存溢出）。
 *
 * <p>支持的 dataType (数据类型) 及其清洗规则:
 * <ul>
 *   <li>"POST"    – 主帖/笔记记录。过滤规则：必须满足 liked_count（点赞数） ≥ 50，过滤掉低质量水帖。</li>
 *   <li>"COMMENT" – 评论记录。过滤规则：只有当 like_count（点赞数） &lt; 0，或 content（内容）长度 &lt; 3 时才丢弃。</li>
 * </ul>
 */
@Service
public class XhsJsonlPreProcessorImpl implements DataPreProcessor {

    private static final Logger log = LoggerFactory.getLogger(XhsJsonlPreProcessorImpl.class);

    // ── 阈值常量定义 ──────────────────────────────────────────────────────────
    private static final int POST_MIN_LIKES    = 50;  // 主帖最低点赞数阈值
    private static final int COMMENT_MIN_LIKES = 0;   // 评论最低点赞数（仅丢弃明确的负数异常值，“0”赞是正常的新评论，需保留）
    private static final int COMMENT_MIN_LEN   = 3;   // 评论最低长度（抛弃诸如“顶”、“卡”、“插眼”等单字符或双字符的无意义噪音数据）

    private final ObjectMapper objectMapper;

    public XhsJsonlPreProcessorImpl(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    // -------------------------------------------------------------------------
    // 开放 API 层
    // -------------------------------------------------------------------------

    @Override
    public String process(String inputFilePath, String outputFilePath, String dataType) {
        // 输出路径由调用方指定；实现内部负责兜底创建父目录，降低 Controller 使用成本。
        Path outputPath = Paths.get(outputFilePath);

        // ── Step 1：自动兜底创建目录 ──────────────────────────────────────────
        // 自动创建输出文件所在的父级目录树，确保后续写入时不会报 NoSuchFileException。
        // 上层调用方无需关心目录存不存在，直接传目标路径即可。
        Path parentDir = outputPath.getParent();
        if (parentDir != null && !Files.exists(parentDir)) {
            try {
                Files.createDirectories(parentDir);
                log.info("[ETL] Created output directory: {}", parentDir);
            } catch (IOException e) {
                return "ERROR: could not create output directory [" + parentDir + "]: " + e.getMessage();
            }
        }

        long totalLines    = 0;  // 总读取行数
        long keptLines     = 0;  // 清洗后保留的有效行数
        long discardedLines = 0; // 被规则过滤或解析失败丢弃的行数

        // 调试探针日志：用于历史排查编码和字段问题，不参与清洗业务判断。
        dbgLog("D", "process:entry", "ETL start",
                String.format("{\"inputFile\":\"%s\",\"dataType\":\"%s\"}",
                        inputFilePath.replace("\\", "\\\\"), dataType));

        // ── Step 2：流式 I/O 处理 (核心抓手) ──────────────────────────────────
        // ⚡ try-with-resources 语法确保无论发生什么异常，流都能被安全关闭，防止文件句柄泄露。
        // 强制使用 UTF-8 字符集，防止因操作系统默认编码不同导致的中文乱码。
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(inputFilePath), StandardCharsets.UTF_8));
             BufferedWriter writer = new BufferedWriter(
                     new OutputStreamWriter(new FileOutputStream(outputFilePath), StandardCharsets.UTF_8))) {

            String rawLine;
            while ((rawLine = reader.readLine()) != null) {
                rawLine = rawLine.trim();
                // 忽略纯空行，防止 JSON 解析报错
                if (rawLine.isEmpty()) {
                    continue;
                }
                totalLines++;

                // 调试探针日志：只采样前几行 COMMENT，帮助定位爬虫字段结构是否变更。
                if (totalLines <= 5 && "COMMENT".equalsIgnoreCase(dataType)) {
                    try {
                        JsonNode probe = objectMapper.readTree(rawLine);
                        String keys = probe.fieldNames().hasNext()
                                ? probe.fieldNames().next() + "…(+" + (probe.size() - 1) + " more)"
                                : "NO_FIELDS";
                        String contentVal = probe.path("content").asText("__MISSING__");
                        String likeVal    = probe.path("like_count").asText("__MISSING__");
                        String nodeType   = probe.getNodeType().toString();
                        dbgLog("A_B_E", "process:loop#" + totalLines, "raw line sample",
                                String.format("{\"nodeType\":\"%s\",\"topLevelKeys\":\"%s\","
                                                + "\"content\":\"%s\",\"like_count\":\"%s\"}",
                                        nodeType,
                                        keys.replace("\"", "'"),
                                        contentVal.replace("\"", "'").replace("\\", "\\\\"),
                                        likeVal.replace("\"", "'")));
                    } catch (Exception probeEx) {
                        dbgLog("D", "process:loop#" + totalLines, "JSON parse failed",
                                String.format("{\"error\":\"%s\",\"rawSnippet\":\"%s\"}",
                                        probeEx.getMessage().replace("\"", "'"),
                                        rawLine.substring(0, Math.min(80, rawLine.length())).replace("\"", "'")));
                    }
                }

                // ── Step 3：按类型路由清洗逻辑 ────────────────────────────────────
                try {
                    JsonNode node = objectMapper.readTree(rawLine);
                    // 采用 Java 14+ 的 switch 表达式进行策略路由
                    ObjectNode cleaned = switch (dataType.toUpperCase()) {
                        case "POST"    -> processPost(node);
                        case "COMMENT" -> processComment(node);
                        default -> throw new IllegalArgumentException("Unknown dataType: " + dataType);
                    };

                    // ✅ 正常路径：清洗逻辑返回了有效节点，序列化后写入目标文件
                    if (cleaned != null) {
                        writer.write(objectMapper.writeValueAsString(cleaned));
                        writer.newLine(); // JSONL 规范：一行一条 JSON
                        keptLines++;
                    } else {
                        // ⚠️ 丢弃路径：数据不满足业务规则（例如点赞不够、内容太短），直接抛弃
                        discardedLines++;
                    }
                } catch (Exception lineEx) {
                    // 容错兜底：当前行若是破坏性的非法 JSON，仅跳过这一行，不中断整个几十万行的批处理任务
                    log.warn("[ETL] Skipping malformed line #{}: {}", totalLines, lineEx.getMessage());
                    // 调试探针日志：记录前几条坏行的异常类型，便于判断是字段缺失还是 JSON 损坏。
                    if (totalLines <= 20) {
                        dbgLog("C", "process:catch#" + totalLines, "line exception",
                                String.format("{\"exType\":\"%s\",\"msg\":\"%s\"}",
                                        lineEx.getClass().getSimpleName(),
                                        lineEx.getMessage() == null ? "null" : lineEx.getMessage().replace("\"", "'")));
                    }
                    discardedLines++;
                }
            }

        } catch (IOException e) {
            // 致命异常：磁盘满了、文件权限不够等系统级 I/O 错误，必须终止并上报
            log.error("[ETL] Fatal I/O error during processing", e);
            return "ERROR: " + e.getMessage();
        }

        // ── Step 4：统计与汇总反馈 ──────────────────────────────────────────
        String summary = String.format(
                "[ETL Done] dataType=%s | total=%d | kept=%d | discarded=%d | output=%s",
                dataType, totalLines, keptLines, discardedLines, outputFilePath);
        log.info(summary);
        return summary;
    }

    // -------------------------------------------------------------------------
    // 记录级清洗处理器 (Record-level handlers)
    // -------------------------------------------------------------------------

    /**
     * ── POST (主帖) 处理逻辑 ─────────────────────────────────────────────
     * 过滤并重塑 POST 记录。精简无用字段，保留关键分析数据。
     * 如果记录由于质量太低（点赞未达标）需要丢弃，则返回 null。
     */
    private ObjectNode processPost(JsonNode node) {
        // 安全读取点赞数，规避 null 或非标准格式报错
        int likedCount = parseCount(node.path("liked_count").asText("0"));
        if (likedCount < POST_MIN_LIKES) {
            return null; // 直接丢弃低赞内容
        }

        ObjectNode out = objectMapper.createObjectNode();
        // 仅提取下游业务需要的核心字段，实现数据瘦身 (Data Shrinking)
        copyTextIfPresent(node, out, "note_id");
        copyTextIfPresent(node, out, "title");
        copyTextIfPresent(node, out, "desc");
        copyTextIfPresent(node, out, "time");

        // ⚡ 兼容性处理坑点：不同版本的爬虫或 API 获取的小红书数据，地域字段名称可能不同。
        // 这里需要做自动回退机制：优先取 'location'，取不到再去尝试取 'ip_location'。
        if (node.hasNonNull("location")) {
            copyTextIfPresent(node, out, "location");
        } else {
            copyTextIfPresent(node, out, "ip_location");
        }

        out.put("liked_count", likedCount); // 写入规范化后的整型点赞数
        copyTextIfPresent(node, out, "user_id");
        return out;
    }

    /**
     * ── COMMENT (评论) 处理逻辑 ──────────────────────────────────────────
     * 过滤并重塑 COMMENT 记录。
     * 如果记录需要丢弃，则返回 null。
     */
    private ObjectNode processComment(JsonNode node) {
        int likeCount = parseCount(node.path("like_count").asText("0"));
        String content = node.path("content").asText("").trim();

        // 调试探针日志：记录评论过滤依据，便于确认 0 赞评论没有被误删。
        dbgLog("A_B", "processComment", "filter check",
                String.format("{\"likeCount\":%d,\"contentLen\":%d,\"contentSnippet\":\"%s\","
                                + "\"likeCountMissing\":%b,\"contentMissing\":%b}",
                        likeCount, content.length(),
                        content.substring(0, Math.min(20, content.length())).replace("\"", "'"),
                        node.path("like_count").isMissingNode(),
                        node.path("content").isMissingNode()));

        // likeCount 默认为 "0"，0 ≥ COMMENT_MIN_LIKES(0)，所以 0 赞评论会被保留。
        // 只有内容极度水（长度<3）或者出现脏数据异常负数赞时才丢弃。
        if (likeCount < COMMENT_MIN_LIKES || content.length() < COMMENT_MIN_LEN) {  // like_count "0" → 0 ≥ 0, kept
            return null;
        }

        ObjectNode out = objectMapper.createObjectNode();
        copyTextIfPresent(node, out, "comment_id");
        copyTextIfPresent(node, out, "note_id"); // 用于外键关联主帖
        out.put("content", content);
        out.put("like_count", likeCount);
        return out;
    }

    // -------------------------------------------------------------------------
    // 内部辅助工具类 (Helper utilities)
    // -------------------------------------------------------------------------

    /**
     * ── 数据规格化：小红书数字转换器 ──────────────────────────────────────────
     * 将小红书特有的带单位的数字字符串（如“1.2w”）转换为标准纯整数。
     *
     * <p>处理场景举例:
     * <ul>
     *   <li>"216"   → 216</li>
     *   <li>"1.2w"  → 12000   (w 或 万 = 10 000)</li>
     *   <li>"1万"   → 10000</li>
     *   <li>"3k"    → 3000    (k = 1 000)</li>
     *   <li>"2.5k"  → 2500</li>
     *   <li>"10000+" → 10000 （安全移除末尾的 '+' 等装饰符）</li>
     * </ul>
     */
    static int parseCount(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0; // 防御性编程：对于空字符串或纯空格，安全视作 0
        }

        // 预处理：清洗多余空格、千分位逗号以及 '+' 号等干扰符号，并统一转小写（针对 W 和 K）
        String s = raw.trim()
                .replace(",", "")
                .replace("+", "")
                .replace("＋", "") // 兼容全角加号
                .toLowerCase();

        try {
            // 匹配 "万" / "w" (乘 10,000)
            if (s.endsWith("万") || s.endsWith("w")) {
                String num = s.substring(0, s.length() - 1);
                return (int) (Double.parseDouble(num) * 10_000);
            }
            // 匹配 "k" (乘 1,000)
            if (s.endsWith("k")) {
                String num = s.substring(0, s.length() - 1);
                return (int) (Double.parseDouble(num) * 1_000);
            }
            // 常规情况：直接解析普通整数或带小数点的数字（强转为 int）
            return (int) Double.parseDouble(s);
        } catch (NumberFormatException e) {
            // 遇到不可预期的奇葩脏字符串时，不抛异常中断程序，安全降级为 0
            log.debug("[ETL] parseCount could not parse '{}', defaulting to 0", raw);
            return 0;
        }
    }

    /**
     * 写入历史调试探针日志。
     *
     * <p>这个方法只服务 ETL 排错，不影响清洗结果；写日志失败时静默忽略，避免调试日志反过来打断批处理。</p>
     */
    private static void dbgLog(String hypothesisId, String location, String message, String data) {
        try {
            String entry = String.format(
                    "{\"sessionId\":\"4a4c38\",\"hypothesisId\":\"%s\",\"location\":\"%s\",\"message\":\"%s\",\"data\":%s,\"timestamp\":%d}%n",
                    hypothesisId, location, message, data, Instant.now().toEpochMilli());
            Path logPath = Paths.get(System.getProperty("user.dir")).getParent()
                    .resolve("agent spring ai").resolve("debug-4a4c38.log");
            Files.writeString(logPath, entry, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) {}
    }

    /**
     * ── 安全拷贝工具方法 ──────────────────────────────────────────────────
     * 如果源 JSON 节点(src)中存在指定的字段且内容不为 null，则将其作为纯文本提取，并复制到目标节点(dst)中。
     * 可有效防止业务层因为空指针或缺少节点引起的崩溃。
     */
    private static void copyTextIfPresent(JsonNode src, ObjectNode dst, String field) {
        JsonNode val = src.path(field); // path() 是安全的，若节点不存在返回 MissingNode 而非 null
        if (!val.isMissingNode() && !val.isNull()) {
            dst.put(field, val.asText());
        }
    }
}
