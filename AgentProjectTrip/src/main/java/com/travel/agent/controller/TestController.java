package com.travel.agent.controller;

import com.travel.agent.core.GatekeeperAgent;
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
public class TestController {

    private final GatekeeperAgent gatekeeperAgent;

    public TestController(GatekeeperAgent gatekeeperAgent) {
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
        if (message == null || message.isBlank()) {
            return ResponseEntity.badRequest()
                    .body("{\"error\":\"参数 message 不能为空\"}");
        }

        try {
            String routeJson = gatekeeperAgent.routeRequest(message);
            return ResponseEntity.ok(routeJson);
        } catch (Exception e) {
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
