package com.travel.agent.ai.config;

import com.travel.agent.ai.AiModelBeanNames;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.retry.RetryUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.web.client.RestClient;

/**
 * 多模型装配中心（全云端三擎混合架构）
 *
 * <p>兼容 Spring AI 1.0.5+ Builder API，且不依赖 Actuator / Micrometer Bean：
 * <ul>
 *   <li>{@link ObservationRegistry#NOOP} — 无观察者注册表时的安全替身</li>
 *   <li>{@link RetryUtils#DEFAULT_RETRY_TEMPLATE} — 框架内置重试策略</li>
 *   <li>{@code ToolCallingManager} — 不显式注入，由 {@link OpenAiChatModel.Builder} 使用内置默认实现</li>
 * </ul>
 *
 * <p>三擎职责：
 * <pre>
 *   gatekeeperChatModel  → DeepSeek Flash  · 意图路由（Temperature=0.0）
 *   coreChatModel        → DeepSeek Pro    · 复杂行程规划
 *   branchChatModel @Primary → Qwen 百炼   · Function Calling / RAG / ETL
 * </pre>
 */
@Configuration
public class MultiModelConfig {

    // ── DeepSeek（闸门 + 核心大脑，共享 API Key）────────────────────────────────

    @Value("${custom.ai.deepseek.api-key}")
    private String deepseekApiKey;

    @Value("${custom.ai.deepseek.base-url}")
    private String deepseekBaseUrl;

    @Value("${custom.ai.deepseek.flash-model}")
    private String deepseekFlashModel;

    @Value("${custom.ai.deepseek.pro-model}")
    private String deepseekProModel;

    // ── 阿里百炼（分支模型）────────────────────────────────────────────────────

    @Value("${custom.ai.bailian.api-key}")
    private String bailianApiKey;

    @Value("${custom.ai.bailian.base-url}")
    private String bailianBaseUrl;

    @Value("${custom.ai.bailian.model}")
    private String bailianModel;

    // ── Engine 1 · 闸门模型（Gatekeeper）──────────────────────────────────────

    /**
     * DeepSeek Flash：极速、极稳，专用于 Gatekeeper 意图识别。
     * Temperature=0.0，MaxTokens=512，只输出 JSON 路由指令。
     */
    @Bean(name = AiModelBeanNames.GATEKEEPER_CHAT_MODEL)
    public ChatModel gatekeeperChatModel(RestClient.Builder restClientBuilder) {
        OpenAiApi api = buildOpenAiApi(deepseekBaseUrl, deepseekApiKey, restClientBuilder);

        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(deepseekFlashModel)
                .temperature(0.0)
                .maxTokens(512)
                .build();

        return buildChatModel(api, options);
    }

    // ── Engine 2 · 核心大脑（Core Brain）──────────────────────────────────────

    /**
     * DeepSeek Pro：最强推理，用于多国行程规划与深度问答。
     */
    @Bean(name = AiModelBeanNames.CORE_CHAT_MODEL)
    public ChatModel coreChatModel(RestClient.Builder restClientBuilder) {
        OpenAiApi api = buildOpenAiApi(deepseekBaseUrl, deepseekApiKey, restClientBuilder);

        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(deepseekProModel)
                .temperature(0.7)
                .maxTokens(4096)
                .build();

        return buildChatModel(api, options);
    }

    // ── Engine 3 · 分支模型（Branch）— 系统默认 @Primary ───────────────────────

    /**
     * 阿里百炼 Qwen：中文理解强，Function Calling 稳定。
     * {@link Primary} 使其成为 {@code ChatClient.Builder} 的默认注入源，
     * 兼容 MastermindAgent / DataExtractionAgent。
     */
    @Bean(name = AiModelBeanNames.BRANCH_CHAT_MODEL)
    @Primary
    public ChatModel branchChatModel(RestClient.Builder restClientBuilder) {
        OpenAiApi api = buildOpenAiApi(bailianBaseUrl, bailianApiKey, restClientBuilder);

        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(bailianModel)
                .temperature(0.7)
                .maxTokens(4096)
                .build();

        return buildChatModel(api, options);
    }

    // ── 私有工厂方法 ──────────────────────────────────────────────────────────

    /**
     * 构建指向 OpenAI 兼容端点的 {@link OpenAiApi} 客户端。
     *
     * @param baseUrl           API 网关根地址（DeepSeek / 百炼兼容模式）
     * @param apiKey            API 密钥
     * @param restClientBuilder Spring 容器提供的 RestClient.Builder（已应用 AiClientConfig 超时定制）
     */
    private static OpenAiApi buildOpenAiApi(String baseUrl,
                                            String apiKey,
                                            RestClient.Builder restClientBuilder) {
        return OpenAiApi.builder()
                .baseUrl(normalizeBaseUrl(baseUrl))
                .apiKey(apiKey)
                .restClientBuilder(restClientBuilder)
                .build();
    }

    /**
     * 通过官方 Builder 构建 {@link OpenAiChatModel}。
     *
     * <p>不依赖 Spring 容器中的 Micrometer / ToolCalling Bean：
     * <ul>
     *   <li>观察注册表 → {@link ObservationRegistry#NOOP}</li>
     *   <li>重试模板 → {@link RetryUtils#DEFAULT_RETRY_TEMPLATE}</li>
     *   <li>工具调用管理器 → Builder 内置 {@code ToolCallingManager.builder().build()}</li>
     * </ul>
     */
    private static OpenAiChatModel buildChatModel(OpenAiApi openAiApi,
                                                  OpenAiChatOptions defaultOptions) {
        return OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(defaultOptions)
                .retryTemplate(RetryUtils.DEFAULT_RETRY_TEMPLATE)
                .observationRegistry(ObservationRegistry.NOOP)
                .build();
    }

    /**
     * 规范化 baseUrl：去除末尾斜杠，避免 SDK 拼接路径时出现双斜杠。
     */
    private static String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("OpenAI-compatible baseUrl must not be blank");
        }
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }
}
