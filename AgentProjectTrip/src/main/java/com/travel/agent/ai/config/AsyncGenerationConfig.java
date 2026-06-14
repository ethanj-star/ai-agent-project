package com.travel.agent.ai.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 第八阶段异步生成线程池配置。
 *
 * <p>系统架构位置：AsyncPlanGenerationService -> <b>generationTaskExecutor</b> -> 后台生成线程</p>
 *
 * <p>职责：
 * <ul>
 *   <li>为完整旅行规划生成提供独立线程池，避免长耗时 Graph 阻塞 HTTP 请求线程。</li>
 *   <li>限制并发生成数量，防止开发阶段误点大量请求把模型和工具调用打爆。</li>
 *   <li>为后续替换成消息队列或分布式 worker 保留清晰边界。</li>
 * </ul>
 * </p>
 */
@Configuration
public class AsyncGenerationConfig {

    /**
     * 创建第八阶段专用任务线程池。
     *
     * @return Spring TaskExecutor
     */
    @Bean(name = "generationTaskExecutor")
    public TaskExecutor generationTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        // 常驻 2 个生成线程，保证少量用户可以并发生成，不阻塞 Web 请求线程。
        executor.setCorePoolSize(2);
        // 最多 4 个并发生成，防止开发阶段误点过多请求导致模型调用暴涨。
        executor.setMaxPoolSize(4);
        // 队列保留短时突发能力；超过容量时 Spring 会按线程池策略拒绝，避免无限堆积。
        executor.setQueueCapacity(20);
        // 线程名前缀便于在日志里区分“HTTP 请求线程”和“后台生成线程”。
        executor.setThreadNamePrefix("plan-generation-");
        executor.initialize();
        return executor;
    }
}
