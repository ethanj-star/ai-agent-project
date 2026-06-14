package com.travel.agent.ai.config;

import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

/**
 * AI 客户端网络配置（基础设施层 - 大模型 HTTP 超时控制）。
 *
 * <p>系统架构位置：Spring RestClient.Builder -> <b>AiClientConfig</b> -> Spring AI ChatModel</p>
 *
 * <p>职责：
 * <ul>
 *   <li>统一定制大模型 HTTP 请求的连接超时和读取超时。</li>
 *   <li>避免复杂规划或 ETL 抽取时模型推理较慢，被默认短超时提前打断。</li>
 *   <li>把网络参数集中在一个配置类里，避免各个 Agent 自己创建 RestClient。</li>
 * </ul>
 * </p>
 */
@Configuration
public class AiClientConfig {

    /**
     * 定制 Spring Boot 自动提供的 RestClient.Builder。
     *
     * @return RestClientCustomizer，供 Spring AI 构建 OpenAI 兼容客户端时复用
     */
    @Bean
    public RestClientCustomizer restClientCustomizer() {
        return restClientBuilder -> {
            SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
            // 建立连接只需要验证网络是否可达，10 秒足够暴露 DNS 或网关问题。
            requestFactory.setConnectTimeout(10000);
            // 读取响应要覆盖大模型推理时间；规划和 ETL 比普通 HTTP 请求慢得多。
            requestFactory.setReadTimeout(90000);

            // 把 requestFactory 装回 builder，后续所有 Spring AI ChatModel 都会继承这个超时配置。
            restClientBuilder.requestFactory(requestFactory);
        };
    }
}
