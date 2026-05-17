package com.travel.agent.web;

import com.travel.agent.core.etl.DataExtractionAgent;
import com.travel.agent.core.etl.DataPreProcessor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.CompletableFuture;

/**
 * REST entry-point for the XHS ETL pipeline.
 *
 * <p>Available endpoints:
 * <pre>
 *   GET /api/v1/etl/clean-xhs?fileName=search_contents_2026-05-16.jsonl&type=POST
 *   GET /api/v1/etl/extract?inputFileName=post_search_contents_2026-05-16.jsonl
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/etl")
public class EtlController {

    /**
     * Root directory that holds the raw JSONL exports from MediaCrawler.
     * Uses a Windows absolute path; adjust if the files are moved.
     */
    private static final String INPUT_DIR =
            "C:\\Users\\DJI\\Desktop\\red note mediacrawler\\jsonl data\\xhs\\jsonl\\";

    /**
     * Root directory for cleaned output files, resolved relative to the JVM's
     * working directory (i.e. the project root when run via Maven/IDE).
     */
    private static final String OUTPUT_DIR =
            System.getProperty("user.dir") + "/data/processed/";

    private final DataPreProcessor    dataPreProcessor;
    private final DataExtractionAgent dataExtractionAgent;

    public EtlController(DataPreProcessor dataPreProcessor,
                         DataExtractionAgent dataExtractionAgent) {
        this.dataPreProcessor    = dataPreProcessor;
        this.dataExtractionAgent = dataExtractionAgent;
    }

    /**
     * Trigger the ETL cleaning pipeline for a single XHS JSONL file.
     *
     * @param fileName the bare file name, e.g. "search_contents_2026-05-16.jsonl"
     * @param type     record type – "POST" for notes, "COMMENT" for comments
     * @return a summary string: total / kept / discarded line counts
     */
    @GetMapping("/clean-xhs")
    public ResponseEntity<String> cleanXhs(
            @RequestParam String fileName,
            @RequestParam String type) {

        if (fileName == null || fileName.isBlank()) {
            return ResponseEntity.badRequest().body("ERROR: 'fileName' parameter must not be empty.");
        }
        if (type == null || (!type.equalsIgnoreCase("POST") && !type.equalsIgnoreCase("COMMENT"))) {
            return ResponseEntity.badRequest()
                    .body("ERROR: 'type' must be either 'POST' or 'COMMENT', got: " + type);
        }

        String inputFilePath  = INPUT_DIR  + fileName;
        String outputFilePath = OUTPUT_DIR + type.toLowerCase() + "_" + fileName;

        String result = dataPreProcessor.process(inputFilePath, outputFilePath, type.toUpperCase());

        if (result.startsWith("ERROR:")) {
            return ResponseEntity.internalServerError().body(result);
        }
        return ResponseEntity.ok(result);
    }

    /**
     * Launch an async LLM entity-extraction task over a previously cleaned JSONL file.
     *
     * <p>The task runs in a background thread so the HTTP call returns immediately.
     * Progress and errors are visible in the application log.
     *
     * @param inputFileName  bare file name inside {@code data/processed/},
     *                       e.g. "post_search_contents_2026-05-16.jsonl"
     * @param outputFileName target file name inside {@code data/extracted/};
     *                       defaults to "extracted_entities.jsonl"
     * @return confirmation message; check server logs for progress
     */
    @GetMapping("/extract")
    public ResponseEntity<String> extract(
            @RequestParam String inputFileName,
            @RequestParam(defaultValue = "extracted_entities.jsonl") String outputFileName) {

        if (inputFileName == null || inputFileName.isBlank()) {
            return ResponseEntity.badRequest().body("ERROR: 'inputFileName' must not be empty.");
        }

        // Fire-and-forget: run extraction in a virtual/platform thread so the HTTP response
        // returns immediately without blocking the caller.
        CompletableFuture.runAsync(() ->
                dataExtractionAgent.runExtractionTask(inputFileName, outputFileName));

        return ResponseEntity.accepted()
                .body(String.format(
                        "后台提取任务已启动 — input: %s, output: data/extracted/%s。请查看服务日志跟踪进度。",
                        inputFileName, outputFileName));
    }
}
