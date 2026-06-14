package com.travel.agent.ai.graph.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 核心模型输出的单个分支任务建议。
 *
 * <p>系统架构位置：ModelBranchDispatchNode -> <b>BranchTaskSuggestion</b> -> BranchDispatchGuardNode</p>
 *
 * <p>职责：
 * <ul>
 *   <li>承载 DeepSeek Pro 对“应该调用哪个分支任务”的建议。</li>
 *   <li>只表达任务类型、优先级和原因，不承载真实工具参数。</li>
 *   <li>由 Java Guard 再转换为 {@link BranchTask}，避免模型直接控制外部工具调用。</li>
 * </ul>
 * </p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class BranchTaskSuggestion {

    /** 模型建议的任务类型，保持 String 是为了接住未知类型并交给 Guard 拒绝。 */
    private String type;

    /** 模型给出的优先级，第一版支持 HIGH / MEDIUM / LOW，未知值按 MEDIUM 处理。 */
    private String priority;

    /** 模型说明为什么需要这个分支任务，后续可进入 Trace 或调试面板。 */
    private String reason;

    public BranchTaskSuggestion() {
    }

    public BranchTaskSuggestion(String type, String priority, String reason) {
        this.type = type;
        this.priority = priority;
        this.reason = reason;
    }

    /**
     * 返回规范化后的任务类型文本。
     *
     * <p>模型可能输出小写、空格或连字符。这里统一转成枚举风格文本，方便 Guard 做白名单判断。</p>
     */
    public String normalizedType() {
        return type == null ? "" : type.trim().toUpperCase().replace('-', '_').replace(' ', '_');
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = cleanText(type);
    }

    public String getPriority() {
        return priority;
    }

    public void setPriority(String priority) {
        this.priority = cleanText(priority);
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = cleanText(reason);
    }

    private static String cleanText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
