package org.example.ai.impl.conversation;

import org.example.ai.knowledge.entity.ResolvedEntity;

import java.util.List;

/**
 * Agent 每轮对话的内部指导信息。
 *
 * <p>这不是路由结果，也不直接生成用户可见回复。它只帮助主 Agent
 * 理解指代、承接情绪、收窄检索范围和控制本轮表达节奏。</p>
 */
public record ConversationGuidance(
        String retrievalQuery,
        List<ResolvedEntity> entities,
        String intent,
        String prompt,
        boolean inheritedEntity,
        boolean clarificationSuggested) {

    public ConversationGuidance {
        retrievalQuery = retrievalQuery == null ? "" : retrievalQuery;
        entities = entities == null ? List.of() : List.copyOf(entities);
        intent = intent == null ? "GENERAL" : intent;
        prompt = prompt == null ? "" : prompt;
    }
}
