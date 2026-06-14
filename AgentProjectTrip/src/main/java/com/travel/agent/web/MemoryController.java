package com.travel.agent.web;

import com.travel.agent.ai.dto.UserMemoryRequest;
import com.travel.agent.ai.dto.UserMemoryResponse;
import com.travel.agent.ai.graph.model.MemoryScope;
import com.travel.agent.ai.graph.model.UserMemory;
import com.travel.agent.ai.memory.UserMemoryService;
import com.travel.agent.core.service.UserContextResolver;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 用户记忆 HTTP 入口（Web 层 - 第七阶段记忆管理）。
 *
 * <p>系统架构位置：<b>Web 层</b> -> UserMemoryService -> UserMemoryStore</p>
 *
 * <p>职责：
 * <ul>
 *   <li>提供用户记忆查询、新增和禁用接口。</li>
 *   <li>让长期偏好可见、可控，避免 Agent 黑箱记忆。</li>
 *   <li>为阶段 6.5 调试台和后续正式前端提供记忆管理入口。</li>
 * </ul>
 * </p>
 */
@RestController
@RequestMapping("/api/v1/memories")
public class MemoryController {

    /** 用户记忆读写策略服务。 */
    private final UserMemoryService userMemoryService;

    /** 开发期 userId / sessionId 解析器。 */
    private final UserContextResolver userContextResolver;

    /**
     * 构造记忆管理入口。
     *
     * @param userMemoryService  用户记忆服务
     * @param userContextResolver 用户上下文解析器
     */
    public MemoryController(UserMemoryService userMemoryService, UserContextResolver userContextResolver) {
        this.userMemoryService = userMemoryService;
        this.userContextResolver = userContextResolver;
    }

    /**
     * 查询用户生效记忆。
     *
     * @param userId    可选用户 ID
     * @param sessionId 可选会话 ID；开发期通常传它即可
     * @param scope     可选作用域，LONG_TERM 或 SHORT_TERM
     * @return 用户记忆列表
     */
    @GetMapping
    public ResponseEntity<UserMemoryResponse> list(@RequestParam(required = false) String userId,
                                                   @RequestParam(required = false) String sessionId,
                                                   @RequestParam(required = false) MemoryScope scope) {
        // 开发期前端可能只传 sessionId，这里统一解析成真正用于存储的 userId。
        String resolvedUserId = userContextResolver.resolveUserId(userId, sessionId);
        // 只读取 active 记忆；被用户禁用的偏好不会再进入 Planner 上下文。
        List<UserMemory> memories = userMemoryService.findActive(resolvedUserId, sessionId, scope);
        return ResponseEntity.ok(new UserMemoryResponse(
                resolvedUserId,
                memories,
                memories.isEmpty() ? "当前没有生效记忆。" : "已读取当前用户记忆。"));
    }

    /**
     * 新增一条用户记忆。
     *
     * <p>第一版主要用于手动写入长期偏好，例如“不住青旅”“偏好火车”。</p>
     *
     * @param request 记忆写入请求
     * @return 保存后的用户记忆列表
     */
    @PostMapping
    public ResponseEntity<UserMemoryResponse> create(@RequestBody UserMemoryRequest request) {
        // key/value 是记忆的最小可用结构，例如 key=hotel_preference, value=不住青旅。
        if (request == null || !hasText(request.getKey()) || !hasText(request.getValue())) {
            return ResponseEntity.badRequest().body(new UserMemoryResponse(
                    null,
                    List.of(),
                    "记忆 key 和 value 不能为空。"));
        }

        UserMemory memory = new UserMemory();
        memory.setUserId(request.getUserId());
        memory.setSessionId(request.getSessionId());
        memory.setScope(request.getScope());
        memory.setType(request.getType());
        memory.setKey(request.getKey());
        memory.setValue(request.getValue());
        memory.setSource(request.getSource());
        memory.setConfidence(request.getConfidence());
        memory.setMetadata(request.getMetadata());
        // Service 会补齐 memoryId、默认 scope/type/source 等字段，并持久化。
        UserMemory saved = userMemoryService.save(memory);

        // 保存后返回当前用户的完整生效记忆列表，前端可以直接刷新列表状态。
        List<UserMemory> memories = userMemoryService.findActive(saved.getUserId(), saved.getSessionId(), null);
        return ResponseEntity.ok(new UserMemoryResponse(
                saved.getUserId(),
                memories,
                "已保存用户记忆：" + saved.getKey() + " = " + saved.getValue()));
    }

    /**
     * 禁用一条用户记忆。
     *
     * <p>这里执行软删除，数据库保留审计记录，但 Planner 不再读取该记忆。</p>
     *
     * @param memoryId 记忆 ID
     * @return 操作提示
     */
    @DeleteMapping("/{memoryId}")
    public ResponseEntity<UserMemoryResponse> deactivate(@PathVariable String memoryId) {
        // 这里是软删除：保留记录用于审计，但后续 findActive 不再返回它。
        userMemoryService.deactivate(memoryId);
        return ResponseEntity.ok(new UserMemoryResponse(
                null,
                List.of(),
                "已禁用记忆：" + memoryId));
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
