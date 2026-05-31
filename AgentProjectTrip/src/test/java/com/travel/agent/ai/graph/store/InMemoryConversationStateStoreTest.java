package com.travel.agent.ai.graph.store;

import com.travel.agent.ai.graph.model.TravelPlanState;
import com.travel.agent.ai.graph.model.WorkflowStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * InMemoryConversationStateStore 的单元测试。
 *
 * <p>重点验证第二阶段 pending 状态保存、读取和清理能力。</p>
 */
class InMemoryConversationStateStoreTest {

    /**
     * 只有 NEEDS_CLARIFICATION 状态会被保存并读取。
     */
    @Test
    void saveAndFindPendingState() {
        InMemoryConversationStateStore store = new InMemoryConversationStateStore();
        TravelPlanState state = new TravelPlanState();
        state.setWorkflowStatus(WorkflowStatus.NEEDS_CLARIFICATION);

        store.savePendingState("s1", state);

        assertThat(store.findPendingState("s1")).containsSame(state);
    }

    /**
     * 工作流完成后应能清理 pending 状态。
     */
    @Test
    void clearPendingState() {
        InMemoryConversationStateStore store = new InMemoryConversationStateStore();
        TravelPlanState state = new TravelPlanState();
        state.setWorkflowStatus(WorkflowStatus.NEEDS_CLARIFICATION);

        store.savePendingState("s1", state);
        store.clearPendingState("s1");

        assertThat(store.findPendingState("s1")).isEmpty();
    }
}
