package com.travel.agent.ai.agents;

import com.travel.agent.ai.tools.FlightTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

@Service
public class MastermindAgent {

    private final ChatClient chatClient;
    private final FlightTools flightTools;

    public MastermindAgent(ChatClient.Builder builder, FlightTools flightTools) {
        this.chatClient = builder.build();
        this.flightTools = flightTools;
    }

    public String chat(String userMessage) {
        String currentDate = java.time.LocalDate.now().toString();
        return chatClient.prompt()
                .system("你是一位极具极客范儿的欧洲旅行总指挥 Agent。你的任务是帮用户查机票并规划行程。" +
                        "你必须自主思考何时调用 'searchFlights' 工具获取底层真实数据。" +
                        "拿到数据后，请用清晰、专业的自然语言向用户总结，不要直接丢出冷冰冰的 JSON。" +
                        " 当前现实世界的系统日期是：" + currentDate + "。你在规划行程和推算时间窗口时，必须严格基于这个当前日期进行计算！")
                .user(userMessage)
                .tools(flightTools)
                .call()
                .content();
    }
}
