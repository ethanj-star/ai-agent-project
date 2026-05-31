package com.travel.agent.web;

import com.travel.agent.ai.agents.DataExtractionAgent;
import com.travel.agent.core.etl.DataPreProcessor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.CompletableFuture;

/**
 * ── 小红书 ETL 数据管道的 REST API 入口 ──────────────────────────────────────
 *
 * <p>提供给外部触发数据清洗和实体提取任务的 HTTP 接口。
 * <p>可用的 API 端点示例:
 * <pre>
 *   1. 触发清洗：GET /api/v1/etl/clean-xhs?fileName=search_contents_2026-05-16.jsonl&type=POST
 *   2. 触发提取：GET /api/v1/etl/extract?inputFileName=post_search_contents_2026-05-16.jsonl
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/etl")
public class EtlController {

    // ── 目录配置 ──────────────────────────────────────────────────────────────

    /**
     * ⚠️ 原始文件目录：存放 MediaCrawler 爬虫导出的原始 JSONL 文件的位置。
     * 当前硬编码为 Windows 系统的绝对路径，如果项目迁移到 Linux 或更改了目录，请务必修改此处。
     */
    private static final String INPUT_DIR =
            "C:\\Users\\DJI\\Desktop\\red note mediacrawler\\jsonl data\\xhs\\jsonl\\";

    /**
     * ✅ 清洗后文件目录：相对于 JVM 工作目录（通常是基于 Maven/IDE 运行时的项目根目录）解析。
     * 清洗后的瘦身数据会存放在此处，供后续的 LLM 提取任务使用。
     */
    private static final String OUTPUT_DIR =
            System.getProperty("user.dir") + "/data/processed/";

    private final DataPreProcessor    dataPreProcessor;
    private final DataExtractionAgent dataExtractionAgent;

    // 推荐的 Spring 构造器注入方式，自动注入依赖组件
    public EtlController(DataPreProcessor dataPreProcessor,
                         DataExtractionAgent dataExtractionAgent) {
        this.dataPreProcessor    = dataPreProcessor;
        this.dataExtractionAgent = dataExtractionAgent;
    }

    /**
     * ── API 1：触发小红书 JSONL 文件的 ETL 清洗流程 ──────────────────────────
     * 同步执行：因为基于本地流式读写，速度较快，HTTP 请求会阻塞直到清洗完成并返回统计结果。
     *
     * @param fileName 纯文件名（不含路径），例如 "search_contents_2026-05-16.jsonl"
     * @param type     记录类型 – "POST" 表示清洗主帖记录，"COMMENT" 表示清洗评论记录
     * @return 返回包含 总行数 / 保留行数 / 丢弃行数 的摘要说明字符串
     */
    @GetMapping("/clean-xhs")
    public ResponseEntity<String> cleanXhs(
            @RequestParam String fileName,
            @RequestParam String type) {

        // 参数基本校验：防止传空值导致后续文件路径拼接错误
        if (fileName == null || fileName.isBlank()) {
            return ResponseEntity.badRequest().body("ERROR: 'fileName' parameter must not be empty.");
        }
        if (type == null || (!type.equalsIgnoreCase("POST") && !type.equalsIgnoreCase("COMMENT"))) {
            return ResponseEntity.badRequest()
                    .body("ERROR: 'type' must be either 'POST' or 'COMMENT', got: " + type);
        }

        // 拼接完整的绝对路径
        // 注意：输出文件会自动加上前缀（例如 post_search_contents...）以区分不同类型的数据
        String inputFilePath  = INPUT_DIR  + fileName;
        String outputFilePath = OUTPUT_DIR + type.toLowerCase() + "_" + fileName;

        // 执行核心的流式清洗逻辑
        String result = dataPreProcessor.process(inputFilePath, outputFilePath, type.toUpperCase());

        // 根据执行结果返回对应的 HTTP 状态码
        if (result.startsWith("ERROR:")) {
            return ResponseEntity.internalServerError().body(result);
        }
        return ResponseEntity.ok(result);
    }

    /**
     * ── API 2：启动异步大模型 (LLM) 实体提取任务 ──────────────────────────────
     * 读取之前清洗好的 JSONL 文件，并调用大模型提取结构化数据。
     *
     * <p>⚡ 性能设计说明：大模型 API 调用极其耗时（且有速率限制），如果同步执行必定导致 HTTP 超时。
     * 因此这里采用了“Fire-and-forget (发后即忘)”的异步线程模式，接口直接返回“已受理”，任务在后台默默运行。
     *
     * @param inputFileName  位于 {@code data/processed/} 目录下的纯文件名，
     *                       例如 "post_search_contents_2026-05-16.jsonl"
     * @param outputFileName 提取结果要写入的目标文件名，保存在 {@code data/extracted/} 目录下；
     *                       如果调用方不传，则默认使用 "extracted_entities.jsonl"
     * @return 立即返回 HTTP 202 Accepted 和确认信息，提示用户去查看后台服务日志跟踪进度
     */
    @GetMapping("/extract")
    public ResponseEntity<String> extract(
            @RequestParam String inputFileName,
            @RequestParam(defaultValue = "extracted_entities.jsonl") String outputFileName) {

        if (inputFileName == null || inputFileName.isBlank()) {
            return ResponseEntity.badRequest().body("ERROR: 'inputFileName' must not be empty.");
        }

        // ⚡ 核心机制：Fire-and-forget (发后即忘)
        // 将提取任务丢入 ForkJoinPool 后台线程池中（如果在 JDK 21+ 且开启了虚拟线程，则由虚拟线程执行）。
        // 这样可以确保当前的 HTTP 响应能够立即返回给客户端，而不会长时间阻塞调用方。
        CompletableFuture.runAsync(() ->
                dataExtractionAgent.runExtractionTask(inputFileName, outputFileName));

        // 告知客户端任务已受理 (HTTP 202)
        return ResponseEntity.accepted()
                .body(String.format(
                        "后台提取任务已启动 — input: %s, output: data/extracted/%s。请查看服务日志跟踪进度。",
                        inputFileName, outputFileName));
    }
}
