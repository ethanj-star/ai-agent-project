package com.travel.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

/**
 * 欧洲旅行 AI Agent 应用程序入口
 *
 * <p>{@link SpringBootApplication} 是组合注解，等价于同时声明：
 * {@code @Configuration}、{@code @EnableAutoConfiguration}、{@code @ComponentScan}，
 * 触发 Spring Boot 的自动配置机制（包括 Spring AI、Redis、RestClient 等）。</p>
 *
 * <p>{@link EnableCaching} 激活 Spring Cache 抽象层，配合 {@code application.properties}
 * 中的 {@code spring.cache.type=redis} 配置，将所有 {@code @Cacheable} 注解的方法
 * 结果自动存储到 Redis，实现航班查询结果的分布式缓存。</p>
 */
@SpringBootApplication
@EnableCaching
public class TravelAgentApplication {

    public static void main(String[] args) {
        // 启动 Spring 容器后，Web Controller、Service、Agent、工具和缓存配置都会被自动扫描注册。
        SpringApplication.run(TravelAgentApplication.class, args);
    }
}
