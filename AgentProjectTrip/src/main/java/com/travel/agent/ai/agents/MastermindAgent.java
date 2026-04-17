package com.travel.agent.ai.agents;

import com.travel.agent.ai.tools.FlightTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

/**
 * 旅行 AI 总指挥 Agent（AI 层 - 核心调度器）
 *
 * <p>系统架构位置：Web 层 → <b>Agent 层</b> → Tools 层 → Service 层</p>
 *
 * <p>职责：
 * <ul>
 *   <li>持有并驱动 {@link ChatClient}，作为与大模型（阿里云百炼 DashScope）对话的唯一入口。</li>
 *   <li>通过 System Prompt 为大模型注入角色定义与实时上下文（当前日期），
 *       解决大模型的"时间幻觉"问题。</li>
 *   <li>将 {@link FlightTools} 注册为可调用工具，授权大模型在推理过程中自主
 *       决策何时触发真实的航班查询（Function Calling）。</li>
 * </ul>
 * </p>
 */
@Service
public class MastermindAgent {

    private final ChatClient chatClient;
    private final FlightTools flightTools;

    /**
     * 通过构造器注入 ChatClient 和工具集。
     *
     * @param builder      Spring AI 提供的 {@link ChatClient.Builder}，已由自动配置绑定到
     *                     application.properties 中的 DashScope 端点和模型参数。
     * @param flightTools  航班查询工具集，注册后大模型可在推理链中主动调用。
     */
    public MastermindAgent(ChatClient.Builder builder, FlightTools flightTools) {
        this.chatClient = builder.build();
        this.flightTools = flightTools;
    }

    /**
     * 接收用户自然语言消息，驱动大模型进行多轮推理并返回最终答复。
     *
     * <p>核心流程：
     * <ol>
     *   <li>在方法入口处获取宿主机的真实系统日期，动态注入 System Prompt，
     *       确保大模型在推算"明天"、"下周"等相对时间时基于真实日期而非训练数据中的固化时间点。</li>
     *   <li>通过 {@code .tools(flightTools)} 将工具集注册到本次对话上下文，
     *       大模型可在推理过程中自主决定是否调用 {@code searchFlights} 工具。</li>
     *   <li>通过 {@code .call().content()} 触发同步阻塞式推理，返回最终的自然语言字符串答复。</li>
     * </ol>
     * </p>
     *
     * @param userMessage 用户输入的自然语言请求，例如："帮我查一下明天从都柏林飞巴黎的机票"
     * @return 大模型经过推理（含可能的工具调用）后生成的自然语言答复
     */
    public String chat(String userMessage) {
        // 在每次请求时动态获取真实系统日期（格式：YYYY-MM-DD），注入 System Prompt
        // 这是解决大模型"时间幻觉"的关键手段：大模型无法感知当前时间，必须由宿主机显式告知
        String currentDate = java.time.LocalDate.now().toString();

        return chatClient.prompt()
                .system("你是一位极具极客范儿的欧洲旅行总指挥 Agent。你的任务是帮用户查机票并规划行程。" +
                        "你必须自主思考何时调用 'searchFlights' 工具获取底层真实数据。" +
                        "拿到数据后，请用清晰、专业的自然语言向用户总结，不要直接丢出冷冰冰的 JSON。" +
                        // 动态拼接当前日期，强制大模型以真实日期为基准计算相对时间
                        " 当前现实世界的系统日期是：" + currentDate + "。你在规划行程和推算时间窗口时，必须严格基于这个当前日期进行计算！")
                .user(userMessage)
                // 注册工具集：大模型在推理中若判断需要查询航班，将自动回调 FlightTools.searchFlights()
                .tools(flightTools)
                .call()
                .content();
    }
}
