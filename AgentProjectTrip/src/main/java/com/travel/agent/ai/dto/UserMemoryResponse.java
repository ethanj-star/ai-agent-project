package com.travel.agent.ai.dto;

import com.travel.agent.ai.graph.model.UserMemory;

import java.util.ArrayList;
import java.util.List;

/**
 * 用户记忆响应 DTO。
 *
 * <p>系统架构位置：MemoryController -> <b>UserMemoryResponse</b> -> 前端 / 调试台</p>
 *
 * <p>职责：
 * <ul>
 *   <li>统一返回当前用户 ID、记忆列表和面向用户的提示语。</li>
 *   <li>让前端可以在一个响应中展示记忆操作结果和最新记忆状态。</li>
 * </ul>
 * </p>
 */
public class UserMemoryResponse {

    /** 当前记忆所属用户 ID。 */
    private String userId;

    /** 当前返回的记忆列表。 */
    private List<UserMemory> memories = new ArrayList<>();

    /** 面向用户或调试台的提示语。 */
    private String assistantMessage;

    public UserMemoryResponse() {
    }

    public UserMemoryResponse(String userId, List<UserMemory> memories, String assistantMessage) {
        this.userId = userId;
        this.memories = memories == null ? new ArrayList<>() : memories;
        this.assistantMessage = assistantMessage;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public List<UserMemory> getMemories() {
        return memories;
    }

    public void setMemories(List<UserMemory> memories) {
        this.memories = memories == null ? new ArrayList<>() : memories;
    }

    public String getAssistantMessage() {
        return assistantMessage;
    }

    public void setAssistantMessage(String assistantMessage) {
        this.assistantMessage = assistantMessage;
    }
}
