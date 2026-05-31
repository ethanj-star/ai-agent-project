package com.travel.agent.web;

import com.travel.agent.ai.agents.MastermindAgent;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 多智能体编排工作流的 HTTP 入口（Web 层）
 *
 * <p>系统架构位置：<b>Web 层</b> → MastermindAgent → [GatekeeperAgent → 路由] → 各模型分支
 *
 * <p>与 {@link TravelController#chat(String)} 的区别：
 * <ul>
 *   <li>此接口走完整三擎编排流：Gatekeeper 识别意图 → 按 intent 分发到对应模型。</li>
 *   <li>{@code TravelController.chat} 走单模型全工具兜底模式（保留向后兼容）。</li>
 * </ul>
 *
 * <p>接口清单：
 * <pre>
 *   GET /api/v1/agent/chat?message=帮我规划国庆节去法国的10天行程
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/agent")
public class AgentController {

    private final MastermindAgent mastermindAgent;

    public AgentController(MastermindAgent mastermindAgent) {
        this.mastermindAgent = mastermindAgent;
    }

    /**
     * 完整多智能体工作流对话接口。
     *
     * <p>请求流程：
     * <ol>
     *   <li>GatekeeperAgent（DeepSeek Flash）识别意图，输出路由 JSON。</li>
     *   <li>MastermindAgent 按 {@code intent} 分发：
     *       <ul>
     *         <li>{@code DIRECT_CHAT} → 写死友好引导语（零 Token）</li>
     *         <li>{@code TOOL_WEATHER / TOOL_FLIGHT} → branchChatClient（Qwen 百炼）</li>
     *         <li>{@code PLAN_OR_RAG} → coreChatClient（DeepSeek Pro）</li>
     *       </ul>
     *   </li>
     * </ol>
     *
     * @param message 用户自然语言输入，例如："帮我规划国庆节去法意瑞的10天行程"
     * @return 最终自然语言答复；出错时返回 500 + 错误说明
     */
    @GetMapping("/chat")
    public ResponseEntity<String> chat(@RequestParam String message,
                                       @RequestParam(required = false) String sessionId) {
        if (message == null || message.isBlank()) {
            return ResponseEntity.badRequest().body("参数 message 不能为空。");
        }

        try {
            String reply = mastermindAgent.handleUserWorkflow(message, sessionId);
            return ResponseEntity.ok(reply);
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body("编排大脑处理异常，请稍后重试。原因：" + e.getMessage());
        }
    }
}
