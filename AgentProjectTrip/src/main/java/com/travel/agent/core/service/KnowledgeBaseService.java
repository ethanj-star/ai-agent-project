package com.travel.agent.core.service;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class KnowledgeBaseService {

    private final VectorStore vectorStore;

    public KnowledgeBaseService(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    public void ingestSampleData() {
        Document doc1 = new Document(
                "【冰岛极光攻略】每年11月至次年3月是冰岛看极光的最佳季节。建议自驾前往辛格韦德利国家公园，远离雷克雅未克市区光污染。气温极低，务必穿戴防风防水级别的羽绒服和防滑冰爪。",
                Map.of("country", "Iceland", "topic", "极光")
        );

        Document doc2 = new Document(
                "【巴黎防坑指南】在卢浮宫和埃菲尔铁塔附近，要特别小心主动帮你戴手绳或让你签字的人，这通常是诈骗。搭乘地铁1号线时要把双肩包背在胸前。",
                Map.of("country", "France", "topic", "安全")
        );

        Document doc3 = new Document(
                "【瑞士火车通票】购买 Swiss Travel Pass 非常划算，不仅可以无限次乘坐绝大多数火车、游船，还能免费进入全国 500 多家博物馆。推荐乘坐冰川快车（Glacier Express），但需提前强制订座。",
                Map.of("country", "Switzerland", "topic", "交通")
        );

        vectorStore.add(List.of(doc1, doc2, doc3));
    }
}
