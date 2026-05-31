package com.travel.agent.ai;

/**
 * AI 模型 Bean 名称常量。
 *
 * <p>系统架构位置：AI Config -> <b>AiModelBeanNames</b> <- AI Agents / Graph Nodes</p>
 *
 * <p>职责：
 * <ul>
 *   <li>统一保存 gatekeeper、core、branch 三个 ChatModel Bean 名称。</li>
 *   <li>避免 Agent 反向依赖具体配置类，保持 ai.agents 和 ai.config 的边界清晰。</li>
 *   <li>减少字符串手写错误，并让 IDE 更容易解析 {@code @Qualifier}。</li>
 * </ul>
 * </p>
 */
public final class AiModelBeanNames {

    /** DeepSeek Flash 路由模型 Bean 名称。 */
    public static final String GATEKEEPER_CHAT_MODEL = "gatekeeperChatModel";

    /** DeepSeek Pro 核心规划模型 Bean 名称。 */
    public static final String CORE_CHAT_MODEL = "coreChatModel";

    /** Qwen 分支模型 Bean 名称，同时作为系统默认 ChatModel。 */
    public static final String BRANCH_CHAT_MODEL = "branchChatModel";

    private AiModelBeanNames() {
    }
}
