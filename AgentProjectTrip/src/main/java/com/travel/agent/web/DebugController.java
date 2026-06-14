package com.travel.agent.web;

import com.travel.agent.ai.agents.GatekeeperAgent;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Gatekeeper 等组件的联调测试接口（仅用于开发验证）。
 *
 * <p>示例：
 * <pre>
 *   GET /api/test/gatekeeper?message=帮我查明天去巴黎的机票
 * </pre>
 */
@RestController
@RequestMapping("/api/test")
public class DebugController {

    private final GatekeeperAgent gatekeeperAgent;

    public DebugController(GatekeeperAgent gatekeeperAgent) {
        this.gatekeeperAgent = gatekeeperAgent;
    }

    /**
     * 调用 Gatekeeper 进行意图路由，原样返回大模型输出的 JSON 字符串。
     *
     * @param message 用户输入的自然语言
     * @return 路由 JSON；失败时返回错误说明
     */
    @GetMapping(value = "/gatekeeper", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> testGatekeeper(@RequestParam String message) {
        // 调试接口也要先校验空消息，避免把无效输入直接送进模型。
        if (message == null || message.isBlank()) {
            return ResponseEntity.badRequest()
                    .body("{\"error\":\"参数 message 不能为空\"}");
        }

        try {
            // 原样返回 Gatekeeper 的 JSON，方便开发时观察意图分类和实体抽取是否正确。
            String routeJson = gatekeeperAgent.routeRequest(message);
            return ResponseEntity.ok(routeJson);
        } catch (Exception e) {
            // 异常响应仍保持 JSON 字符串格式，浏览器和前端调试工具都能直接解析。
            return ResponseEntity.internalServerError()
                    .body("{\"error\":\"Gatekeeper 调用失败: " + escapeJson(e.getMessage()) + "\"}");
        }
    }

    /** 将异常信息中的双引号转义，避免破坏 JSON 结构。 */
    private static String escapeJson(String text) {
        if (text == null) {
            return "unknown";
        }
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
