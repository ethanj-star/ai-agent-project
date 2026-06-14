package com.travel.agent.web;

import com.travel.agent.core.service.KnowledgeBaseService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 知识库（向量 / RAG）REST API 控制器（Web 层）
 *
 * <p>系统架构位置：<b>Web 层</b> → KnowledgeBaseService → VectorStore（Pinecone）
 *
 * <p>提供知识库数据灌入的触发接口，供开发阶段通过浏览器或 curl 手动触发。
 * 生产环境建议将写操作改为 {@code @PostMapping} 并加鉴权。
 *
 * <p>接口清单：
 * <ul>
 *   <li>{@code GET /api/v1/knowledge/ingest?fileName=france_italy_entities.jsonl}
 *       — 将提取好的法意瑞实体 JSONL 向量化灌入 Pinecone</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/knowledge")
public class KnowledgeController {

    private final KnowledgeBaseService knowledgeBaseService;

    public KnowledgeController(KnowledgeBaseService knowledgeBaseService) {
        this.knowledgeBaseService = knowledgeBaseService;
    }

    /**
     * 触发真实旅游实体数据的向量化灌入。
     *
     * <p>调用链：
     * <ol>
     *   <li>读取 {@code data/extracted/} 目录下的指定 JSONL 文件。</li>
     *   <li>逐行解析为 Spring AI {@code Document}（语义文本 + Metadata）。</li>
     *   <li>每 20 条调用一次 {@code vectorStore.add()}，Embedding 后落入 Pinecone。</li>
     * </ol>
     *
     * <p>⚡ 注意：此接口为同步阻塞调用。文件较大时（数百条实体）请求会阻塞较长时间，
     * 属正常现象——每批次需等待 Embedding API 往返延迟。请耐心等待返回。
     *
     * @param fileName 文件名，默认值为 {@code france_italy_entities.jsonl}，
     *                 对应 {@code data/extracted/france_italy_entities.jsonl}
     * @return {@code 200 OK} 成功提示；{@code 400 Bad Request} 参数为空时
     */
    @GetMapping("/ingest")
    public ResponseEntity<String> ingest(
            @RequestParam(defaultValue = "france_italy_entities.jsonl") String fileName) {

        // fileName 为空时无法定位 data/extracted 下的文件，提前返回 400。
        if (fileName.isBlank()) {
            return ResponseEntity.badRequest()
                    .body("ERROR: 'fileName' 参数不能为空。");
        }

        // 具体的文件读取、Document 构造、Embedding 和向量库写入都封装在 Service 层。
        knowledgeBaseService.ingestRealKnowledge(fileName);

        return ResponseEntity.ok(
                "真实知识库数据已成功分批灌入 Pinecone！文件：" + fileName);
    }
}
