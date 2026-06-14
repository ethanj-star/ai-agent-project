package com.travel.agent.ai.graph.node;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.agent.ai.graph.model.BranchDispatchDecision;
import com.travel.agent.ai.graph.model.TravelPlanState;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * ModelBranchDispatchNode 的单元测试。
 *
 * <p>测试重点是模型 JSON 解析和失败降级，不触发真实 DeepSeek Pro 调用。</p>
 */
class ModelBranchDispatchNodeTest {

    private final ModelBranchDispatchNode node = new ModelBranchDispatchNode((ChatClient) null, new ObjectMapper());

    /**
     * 模型返回合法 JSON 时，应解析出任务建议和备注。
     */
    @Test
    void parseDecisionParsesJsonSuggestions() throws Exception {
        BranchDispatchDecision result = node.parseDecision("""
                ```json
                {
                  "tasks": [
                    {
                      "type": "HOTEL",
                      "priority": "HIGH",
                      "reason": "用户提供了日期和住宿偏好，需要真实酒店价格参考。"
                    },
                    {
                      "type": "VISA",
                      "priority": "LOW",
                      "reason": "模型误建议了当前未接入的签证工具。"
                    }
                  ],
                  "notes": ["未来天气工具暂未接入。"]
                }
                ```
                """);

        assertThat(result.isFallbackRequired()).isFalse();
        assertThat(result.getTasks()).hasSize(2);
        assertThat(result.getTasks().get(0).normalizedType()).isEqualTo("HOTEL");
        assertThat(result.getTasks().get(1).normalizedType()).isEqualTo("VISA");
        assertThat(result.getNotes()).contains("未来天气工具暂未接入。");
    }

    /**
     * 模型调用或 JSON 解析失败时，应返回 fallback 决策，而不是向上抛异常。
     */
    @Test
    void dispatchFallsBackWhenModelReturnsInvalidJson() {
        ModelBranchDispatchNode modelNode = new ModelBranchDispatchNode(mock(ChatClient.class), new ObjectMapper()) {
            @Override
            protected String callModel(String systemPrompt, String userQuery) {
                return "我觉得应该查酒店，但我没有返回 JSON";
            }
        };

        BranchDispatchDecision result = modelNode.dispatch(new TravelPlanState());

        assertThat(result.isFallbackRequired()).isTrue();
        assertThat(result.getFallbackReason()).contains("模型派发失败");
    }

    /**
     * 模型返回空任务时，应触发 fallback，让旧规则派发继续兜底。
     */
    @Test
    void dispatchFallsBackWhenModelReturnsNoTasks() {
        ModelBranchDispatchNode modelNode = new ModelBranchDispatchNode(mock(ChatClient.class), new ObjectMapper()) {
            @Override
            protected String callModel(String systemPrompt, String userQuery) {
                return """
                        {
                          "tasks": [],
                          "notes": ["没有建议任何任务"]
                        }
                        """;
            }
        };

        BranchDispatchDecision result = modelNode.dispatch(new TravelPlanState());

        assertThat(result.isFallbackRequired()).isTrue();
        assertThat(result.getFallbackReason()).contains("没有返回任何分支任务");
    }
}
