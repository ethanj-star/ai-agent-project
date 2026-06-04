package com.travel.agent.ai.memory;

import com.travel.agent.ai.graph.model.MemoryScope;
import com.travel.agent.ai.graph.model.MemorySource;
import com.travel.agent.ai.graph.model.MemoryType;
import com.travel.agent.ai.graph.model.TravelRequirementSpec;
import com.travel.agent.ai.graph.model.UserMemory;
import com.travel.agent.ai.graph.store.UserMemoryStore;
import com.travel.agent.core.service.UserContextResolver;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 用户记忆服务（AI 层 - 记忆写入与读取策略）。
 *
 * <p>系统架构位置：MemoryController / RequirementController / LangGraphPlannerFacade -> <b>UserMemoryService</b> -> UserMemoryStore</p>
 *
 * <p>职责：
 * <ul>
 *   <li>统一处理短期记忆和长期记忆的写入策略。</li>
 *   <li>从已确认需求表中提取“本次旅行有效”的短期记忆。</li>
 *   <li>把用户长期偏好压缩成 Planner 可读 prompt 上下文。</li>
 *   <li>避免 Controller 直接操作 Store，保持记忆系统可审计、可控。</li>
 * </ul>
 * </p>
 */
@Service
public class UserMemoryService {

    /** 注入 prompt 的长期记忆最大条数，避免记忆过多挤占规划上下文。 */
    private static final int MAX_PROMPT_MEMORIES = 12;

    /** 用户记忆仓库，可由内存或 JDBC 实现。 */
    private final UserMemoryStore userMemoryStore;

    /** 开发期 userId / sessionId 解析器。 */
    private final UserContextResolver userContextResolver;

    /**
     * 构造用户记忆服务。
     *
     * @param userMemoryStore    用户记忆仓库
     * @param userContextResolver 用户上下文解析器
     */
    public UserMemoryService(UserMemoryStore userMemoryStore, UserContextResolver userContextResolver) {
        this.userMemoryStore = userMemoryStore;
        this.userContextResolver = userContextResolver;
    }

    /**
     * 手动保存一条用户记忆。
     *
     * <p>这个入口用于前端记忆管理和用户明确表达的长期偏好。调用方需要传入 scope/type/source，
     * 服务层只做字段兜底、ID 生成和用户身份归一化。</p>
     *
     * @param memory 待保存记忆
     * @return 保存后的记忆
     */
    public UserMemory save(UserMemory memory) {
        if (memory == null) {
            throw new IllegalArgumentException("memory must not be null");
        }
        if (!hasText(memory.getMemoryId())) {
            memory.setMemoryId("mem-" + UUID.randomUUID());
        }
        String sessionId = userContextResolver.resolveSessionId(memory.getSessionId(), memory.getUserId());
        String userId = userContextResolver.resolveUserId(memory.getUserId(), sessionId);
        memory.setSessionId(sessionId);
        memory.setUserId(userId);
        if (!hasText(memory.getKey())) {
            throw new IllegalArgumentException("memory key must not be blank");
        }
        if (!hasText(memory.getValue())) {
            throw new IllegalArgumentException("memory value must not be blank");
        }
        return userMemoryStore.save(memory);
    }

    /**
     * 查询用户生效记忆。
     *
     * @param userId    用户 ID，可为空
     * @param sessionId 会话 ID，可为空
     * @param scope     可选作用域
     * @return 生效记忆列表
     */
    public List<UserMemory> findActive(String userId, String sessionId, MemoryScope scope) {
        String resolvedUserId = userContextResolver.resolveUserId(userId, sessionId);
        if (scope == null) {
            return userMemoryStore.findActiveByUserId(resolvedUserId);
        }
        return userMemoryStore.findActiveByUserIdAndScope(resolvedUserId, scope);
    }

    /**
     * 禁用一条记忆。
     *
     * @param memoryId 记忆 ID
     */
    public void deactivate(String memoryId) {
        userMemoryStore.deactivate(memoryId);
    }

    /**
     * 从已确认需求表同步本次旅行短期记忆。
     *
     * <p>写入策略：确认需求表代表用户认可“这次旅行”的事实，所以只写 SHORT_TERM。
     * 是否升级为 LONG_TERM 必须由用户明确表达或后续人工确认，避免系统把单次偏好误记为永久偏好。</p>
     *
     * @param spec 已确认需求表
     */
    public void syncFromConfirmedRequirement(TravelRequirementSpec spec) {
        if (spec == null) {
            return;
        }
        String sessionId = userContextResolver.resolveSessionId(spec.getSessionId(), null);
        String userId = userContextResolver.resolveUserId(null, sessionId);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("requirementId", spec.getRequirementId());
        List<UserMemory> candidates = new ArrayList<>();

        addListMemory(candidates, userId, sessionId, "destinations", spec.getDestinations(), metadata);
        addTextMemory(candidates, userId, sessionId, "departureCity", spec.getDepartureCity(), metadata);
        addTextMemory(candidates, userId, sessionId, "startDateText", spec.getStartDateText(), metadata);
        addTextMemory(candidates, userId, sessionId, "durationDays",
                spec.getDurationDays() == null ? null : String.valueOf(spec.getDurationDays()), metadata);
        addTextMemory(candidates, userId, sessionId, "travelerCount",
                spec.getTravelerCount() == null ? null : String.valueOf(spec.getTravelerCount()), metadata);
        addBudgetMemory(candidates, userId, sessionId, spec, metadata);
        addTextMemory(candidates, userId, sessionId, "budgetIncludesInternationalFlight",
                spec.getBudgetIncludesInternationalFlight() == null
                        ? null
                        : String.valueOf(spec.getBudgetIncludesInternationalFlight()), metadata);
        addListMemory(candidates, userId, sessionId, "preferences", spec.getPreferences(), metadata);
        addListMemory(candidates, userId, sessionId, "avoidances", spec.getAvoidances(), metadata);
        addTextMemory(candidates, userId, sessionId, "travelStyle", spec.getTravelStyle(), metadata);
        addTextMemory(candidates, userId, sessionId, "accommodationPreference", spec.getAccommodationPreference(), metadata);
        addTextMemory(candidates, userId, sessionId, "transportPreference", spec.getTransportPreference(), metadata);

        List<UserMemory> existing = userMemoryStore.findActiveByUserIdAndScope(userId, MemoryScope.SHORT_TERM);
        for (UserMemory candidate : candidates) {
            if (!containsSameMemory(existing, candidate)) {
                userMemoryStore.save(candidate);
            }
        }
    }

    /**
     * 构建 Planner 可读的用户记忆上下文。
     *
     * <p>只读取 active 记忆，优先长期记忆，再附带当前会话短期记忆。输出文本明确声明“仅供参考”，
     * 由 PlanDraftNode 继续保证本次需求表优先级最高。</p>
     *
     * @param userId    用户 ID，可为空
     * @param sessionId 会话 ID，可为空
     * @return 可注入 prompt 的记忆摘要；没有记忆时返回“无。”
     */
    public String buildPromptContext(String userId, String sessionId) {
        String resolvedUserId = userContextResolver.resolveUserId(userId, sessionId);
        List<UserMemory> memories = new ArrayList<>();
        memories.addAll(userMemoryStore.findActiveByUserIdAndScope(resolvedUserId, MemoryScope.LONG_TERM));
        memories.addAll(userMemoryStore.findActiveByUserIdAndScope(resolvedUserId, MemoryScope.SHORT_TERM));
        if (memories.isEmpty()) {
            return "无。";
        }

        List<String> lines = new ArrayList<>();
        int count = 0;
        for (UserMemory memory : memories) {
            if (memory == null || !hasText(memory.getKey()) || !hasText(memory.getValue())) {
                continue;
            }
            lines.add("- [" + memory.getScope() + "/" + memory.getType() + "] "
                    + memory.getKey() + " = " + memory.getValue());
            count++;
            if (count >= MAX_PROMPT_MEMORIES) {
                break;
            }
        }
        return lines.isEmpty() ? "无。" : String.join("\n", lines);
    }

    private static void addTextMemory(List<UserMemory> memories,
                                      String userId,
                                      String sessionId,
                                      String key,
                                      String value,
                                      Map<String, Object> metadata) {
        if (!hasText(value)) {
            return;
        }
        memories.add(shortTermMemory(userId, sessionId, key, value.trim(), metadata));
    }

    private static void addListMemory(List<UserMemory> memories,
                                      String userId,
                                      String sessionId,
                                      String key,
                                      List<String> values,
                                      Map<String, Object> metadata) {
        if (values == null || values.isEmpty()) {
            return;
        }
        List<String> cleaned = values.stream()
                .filter(UserMemoryService::hasText)
                .map(String::trim)
                .toList();
        if (!cleaned.isEmpty()) {
            memories.add(shortTermMemory(userId, sessionId, key, String.join("、", cleaned), metadata));
        }
    }

    private static void addBudgetMemory(List<UserMemory> memories,
                                        String userId,
                                        String sessionId,
                                        TravelRequirementSpec spec,
                                        Map<String, Object> metadata) {
        BigDecimal amount = spec.getBudgetAmount();
        if (amount == null) {
            return;
        }
        String value = amount.stripTrailingZeros().toPlainString()
                + (hasText(spec.getBudgetCurrency()) ? spec.getBudgetCurrency().trim() : "");
        memories.add(shortTermMemory(userId, sessionId, "budget", value, metadata));
    }

    private static UserMemory shortTermMemory(String userId,
                                              String sessionId,
                                              String key,
                                              String value,
                                              Map<String, Object> metadata) {
        UserMemory memory = new UserMemory();
        memory.setMemoryId("mem-" + UUID.randomUUID());
        memory.setUserId(userId);
        memory.setSessionId(sessionId);
        memory.setScope(MemoryScope.SHORT_TERM);
        memory.setType(MemoryType.FACT);
        memory.setKey(key);
        memory.setValue(value);
        memory.setSource(MemorySource.CONFIRMED_REQUIREMENT);
        memory.setConfidence(1.0);
        memory.setMetadata(new LinkedHashMap<>(metadata));
        return memory;
    }

    private static boolean containsSameMemory(List<UserMemory> existing, UserMemory candidate) {
        if (existing == null || existing.isEmpty() || candidate == null) {
            return false;
        }
        return existing.stream().anyMatch(memory -> memory != null
                && memory.getScope() == candidate.getScope()
                && same(memory.getKey(), candidate.getKey())
                && same(memory.getValue(), candidate.getValue()));
    }

    private static boolean same(String first, String second) {
        return first == null ? second == null : first.equals(second);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
