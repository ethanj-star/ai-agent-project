package com.travel.agent.web;

import com.travel.agent.core.service.KnowledgeBaseService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/knowledge")
public class KnowledgeController {

    private final KnowledgeBaseService knowledgeBaseService;

    public KnowledgeController(KnowledgeBaseService knowledgeBaseService) {
        this.knowledgeBaseService = knowledgeBaseService;
    }

    @GetMapping("/ingest")
    public String ingest() {
        knowledgeBaseService.ingestSampleData();
        return "测试攻略向量化灌入完成！";
    }
}
