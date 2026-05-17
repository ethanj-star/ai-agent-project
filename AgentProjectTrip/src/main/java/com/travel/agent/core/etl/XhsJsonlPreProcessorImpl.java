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
 * High-performance streaming ETL cleaner for Xiaohongshu (XHS) JSONL exports.
 *
 * <p>Reads line-by-line with {@link BufferedReader} and writes line-by-line with
 * {@link BufferedWriter} to keep memory usage constant regardless of file size.
 *
 * <p>Supported dataType values:
 * <ul>
 *   <li>"POST"    – note/post records, filtered by liked_count ≥ 50</li>
 *   <li>"COMMENT" – comment records, dropped only when like_count &lt; 0 OR content length &lt; 3</li>
 * </ul>
 */
@Service
public class XhsJsonlPreProcessorImpl implements DataPreProcessor {

    private static final Logger log = LoggerFactory.getLogger(XhsJsonlPreProcessorImpl.class);

    private static final int POST_MIN_LIKES    = 50;
    private static final int COMMENT_MIN_LIKES = 0;   // drop only explicit negatives; "0" is kept
    private static final int COMMENT_MIN_LEN   = 3;   // discard single-char noise like "顶"/"卡"

    private final ObjectMapper objectMapper;

    public XhsJsonlPreProcessorImpl(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    @Override
    public String process(String inputFilePath, String outputFilePath, String dataType) {
        Path outputPath = Paths.get(outputFilePath);

        // Auto-create parent directories so the caller never has to pre-create them.
        Path parentDir = outputPath.getParent();
        if (parentDir != null && !Files.exists(parentDir)) {
            try {
                Files.createDirectories(parentDir);
                log.info("[ETL] Created output directory: {}", parentDir);
            } catch (IOException e) {
                return "ERROR: could not create output directory [" + parentDir + "]: " + e.getMessage();
            }
        }

        long totalLines    = 0;
        long keptLines     = 0;
        long discardedLines = 0;

        // #region agent debug log – hypothesis D: encoding probe
        dbgLog("D", "process:entry", "ETL start",
            String.format("{\"inputFile\":\"%s\",\"dataType\":\"%s\"}",
                inputFilePath.replace("\\", "\\\\"), dataType));
        // #endregion

        try (BufferedReader reader = new BufferedReader(
                     new InputStreamReader(new FileInputStream(inputFilePath), StandardCharsets.UTF_8));
             BufferedWriter writer = new BufferedWriter(
                     new OutputStreamWriter(new FileOutputStream(outputFilePath), StandardCharsets.UTF_8))) {

            String rawLine;
            while ((rawLine = reader.readLine()) != null) {
                rawLine = rawLine.trim();
                if (rawLine.isEmpty()) {
                    continue;
                }
                totalLines++;

                // #region agent debug log – hypotheses A/B/C/E: sample first 5 lines
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
                // #endregion

                try {
                    JsonNode node = objectMapper.readTree(rawLine);
                    ObjectNode cleaned = switch (dataType.toUpperCase()) {
                        case "POST"    -> processPost(node);
                        case "COMMENT" -> processComment(node);
                        default -> throw new IllegalArgumentException("Unknown dataType: " + dataType);
                    };

                    if (cleaned != null) {
                        writer.write(objectMapper.writeValueAsString(cleaned));
                        writer.newLine();
                        keptLines++;
                    } else {
                        discardedLines++;
                    }
                } catch (Exception lineEx) {
                    log.warn("[ETL] Skipping malformed line #{}: {}", totalLines, lineEx.getMessage());
                    // #region agent debug log – hypothesis C: exception type probe
                    if (totalLines <= 20) {
                        dbgLog("C", "process:catch#" + totalLines, "line exception",
                            String.format("{\"exType\":\"%s\",\"msg\":\"%s\"}",
                                lineEx.getClass().getSimpleName(),
                                lineEx.getMessage() == null ? "null" : lineEx.getMessage().replace("\"", "'")));
                    }
                    // #endregion
                    discardedLines++;
                }
            }

        } catch (IOException e) {
            log.error("[ETL] Fatal I/O error during processing", e);
            return "ERROR: " + e.getMessage();
        }

        String summary = String.format(
                "[ETL Done] dataType=%s | total=%d | kept=%d | discarded=%d | output=%s",
                dataType, totalLines, keptLines, discardedLines, outputFilePath);
        log.info(summary);
        return summary;
    }

    // -------------------------------------------------------------------------
    // Record-level handlers
    // -------------------------------------------------------------------------

    /**
     * Filter and reshape a POST (note) record.
     * Returns null if the record should be discarded.
     */
    private ObjectNode processPost(JsonNode node) {
        int likedCount = parseCount(node.path("liked_count").asText("0"));
        if (likedCount < POST_MIN_LIKES) {
            return null;
        }

        ObjectNode out = objectMapper.createObjectNode();
        copyTextIfPresent(node, out, "note_id");
        copyTextIfPresent(node, out, "title");
        copyTextIfPresent(node, out, "desc");
        copyTextIfPresent(node, out, "time");

        // location and ip_location are both valid field names in different crawl versions
        if (node.hasNonNull("location")) {
            copyTextIfPresent(node, out, "location");
        } else {
            copyTextIfPresent(node, out, "ip_location");
        }

        out.put("liked_count", likedCount);
        copyTextIfPresent(node, out, "user_id");
        return out;
    }

    /**
     * Filter and reshape a COMMENT record.
     * Returns null if the record should be discarded.
     */
    private ObjectNode processComment(JsonNode node) {
        int likeCount = parseCount(node.path("like_count").asText("0"));
        String content = node.path("content").asText("").trim();

        // #region agent debug log – hypotheses A/B/E: filter decision probe
        dbgLog("A_B", "processComment", "filter check",
            String.format("{\"likeCount\":%d,\"contentLen\":%d,\"contentSnippet\":\"%s\","
                + "\"likeCountMissing\":%b,\"contentMissing\":%b}",
                likeCount, content.length(),
                content.substring(0, Math.min(20, content.length())).replace("\"", "'"),
                node.path("like_count").isMissingNode(),
                node.path("content").isMissingNode()));
        // #endregion

        if (likeCount < COMMENT_MIN_LIKES || content.length() < COMMENT_MIN_LEN) {  // like_count "0" → 0 ≥ 0, kept
            return null;
        }

        ObjectNode out = objectMapper.createObjectNode();
        copyTextIfPresent(node, out, "comment_id");
        copyTextIfPresent(node, out, "note_id");
        out.put("content", content);
        out.put("like_count", likeCount);
        return out;
    }

    // -------------------------------------------------------------------------
    // Helper utilities
    // -------------------------------------------------------------------------

    /**
     * Converts XHS-style count strings to plain integers.
     *
     * <p>Handled formats:
     * <ul>
     *   <li>"216"   → 216</li>
     *   <li>"1.2w"  → 12000   (w = 万 = 10 000)</li>
     *   <li>"1万"   → 10000</li>
     *   <li>"3k"    → 3000</li>
     *   <li>"2.5k"  → 2500</li>
     *   <li>"10000+" → 10000 (trailing '+' stripped)</li>
     * </ul>
     */
    static int parseCount(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0;
        }

        // Strip whitespace and common suffix decorators
        String s = raw.trim()
                      .replace(",", "")
                      .replace("+", "")
                      .replace("＋", "")
                      .toLowerCase();

        try {
            // 万 / w  (10 000)
            if (s.endsWith("万") || s.endsWith("w")) {
                String num = s.substring(0, s.length() - 1);
                return (int) (Double.parseDouble(num) * 10_000);
            }
            // k  (1 000)
            if (s.endsWith("k")) {
                String num = s.substring(0, s.length() - 1);
                return (int) (Double.parseDouble(num) * 1_000);
            }
            // Plain integer or decimal
            return (int) Double.parseDouble(s);
        } catch (NumberFormatException e) {
            log.debug("[ETL] parseCount could not parse '{}', defaulting to 0", raw);
            return 0;
        }
    }

    // #region agent debug log helper
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
    // #endregion

    /** Copies a field as plain text if it is present and non-null in the source node. */
    private static void copyTextIfPresent(JsonNode src, ObjectNode dst, String field) {
        JsonNode val = src.path(field);
        if (!val.isMissingNode() && !val.isNull()) {
            dst.put(field, val.asText());
        }
    }
}
