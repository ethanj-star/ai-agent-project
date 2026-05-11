package com.travel.agent.ai.config;

import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

/**
 * 属于 ai 层：专门用于定制化与大模型交互的基础设施网络配置
 */
@Configuration
public class AiClientConfig {

    @Bean
    public RestClientCustomizer restClientCustomizer() {
        return restClientBuilder -> {
            SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
            // 建立连接的超时时间：10秒
            requestFactory.setConnectTimeout(10000);
            // ⭐️ 核心修复：读取数据的超时时间（大模型推理时间长，设置为 60-90 秒）
            requestFactory.setReadTimeout(90000);

            restClientBuilder.requestFactory(requestFactory);
        };
    }
}