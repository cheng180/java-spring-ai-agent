package org.example.ai.impl.conversation;

import org.example.ai.knowledge.entity.EntityResolver;
import org.example.ai.knowledge.entity.ResolvedEntity;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 构建 Agent 内部的对话指导，不承担回答和分支路由职责。
 *
 * <p>首版使用确定性规则承接高频表达：只有当前消息没有明确车系、且
 * 历史中恰好只有一个候选车系时才自动继承；多个候选时只给主 Agent
 * 一个澄清建议，避免代码替用户猜测。</p>
 */
@Component
public class ConversationGuidanceBuilder {

    private static final List<String> REFERENCE_WORDS = List.of(
            "这个", "这款", "这车", "那款", "那车", "它", "还有吗", "还有没有", "这台", "那台"
    );

    private static final List<String> PRICE_CONCERN_WORDS = List.of(
            "太贵", "贵了", "价格高", "超预算", "预算不够", "便宜点", "更便宜", "嫌贵"
    );

    private static final List<String> HESITATION_WORDS = List.of(
            "再看看", "再考虑", "考虑一下", "感觉一般", "不太满意", "有点纠结", "犹豫", "先不买"
    );

    private static final List<String> PREFERENCE_WORDS = List.of(
            "最便宜", "省油", "家用", "通勤", "七座", "空间大", "性价比", "适合家人"
    );

    private final EntityResolver entityResolver;

    public ConversationGuidanceBuilder(EntityResolver entityResolver) {
        this.entityResolver = entityResolver;
    }

    public ConversationGuidance build(String userMessage, String historyText,
                                      List<ResolvedEntity> currentEntities) {
        String message = userMessage == null ? "" : userMessage.trim();
        String lower = message.toLowerCase(Locale.ROOT);
        List<ResolvedEntity> current = unique(currentEntities);
        boolean hasReference = containsAny(lower, REFERENCE_WORDS);
        boolean vague = hasReference || containsAny(lower, PRICE_CONCERN_WORDS)
                || containsAny(lower, HESITATION_WORDS) || containsAny(lower, PREFERENCE_WORDS);

        List<ResolvedEntity> effective = current;
        boolean inherited = false;
        boolean ambiguous = false;
        if (current.isEmpty() && vague) {
            List<ResolvedEntity> historyEntities = unique(entityResolver.resolve(historyText));
            if (historyEntities.size() == 1) {
                effective = historyEntities;
                inherited = true;
            } else if (historyEntities.size() > 1) {
                ambiguous = true;
            }
        }

        String intent = intentOf(lower);
        String retrievalQuery = message;
        if (inherited) {
            retrievalQuery = effective.get(0).displayName() + " " + message;
        }

        String prompt = buildPrompt(intent, effective, inherited, ambiguous, vague);
        return new ConversationGuidance(retrievalQuery, effective, intent, prompt,
                inherited, ambiguous || (vague && effective.isEmpty()));
    }

    private String buildPrompt(String intent, List<ResolvedEntity> entities,
                               boolean inherited, boolean ambiguous, boolean vague) {
        if (!vague && entities.isEmpty()) return "";

        StringBuilder prompt = new StringBuilder("## 本轮对话理解与服务指导\n");
        if (inherited) {
            prompt.append("客户当前消息承接了上一轮明确车系：")
                    .append(entities.get(0).displayName()).append("。\n");
        }
        if (ambiguous) {
            prompt.append("历史中存在多个可能的车系指代，不能自行猜测；先自然确认客户指的是哪一款。\n");
        } else if (vague && entities.isEmpty()) {
            prompt.append("客户表达较模糊且缺少明确车系，不要凭空推荐；先问一个最关键的澄清问题。\n");
        }

        switch (intent) {
            case "PRICE_CONCERN" -> prompt.append("客户主要在表达价格或预算顾虑：先接住顾虑，再最多询问一个预算或替代方向；不要重新罗列全部库存。\n");
            case "HESITATION" -> prompt.append("客户处于犹豫/观望状态：先共情，不强推，不一次倒出大量参数。\n");
            case "PREFERENCE" -> prompt.append("客户在表达筛选偏好：围绕该偏好给少量匹配建议，最多 3 款，每款一句话概括。\n");
            default -> { }
        }
        prompt.append("这段指导只用于回答策略，不是事实来源；价格、库存、配置和门店信息必须以资料或工具为准。\n")
                .append("本轮只处理一个重点，最多提出一个自然问题，不要输出内部分析。");
        return prompt.toString();
    }

    private static String intentOf(String lower) {
        if (containsAny(lower, PRICE_CONCERN_WORDS)) return "PRICE_CONCERN";
        if (containsAny(lower, HESITATION_WORDS)) return "HESITATION";
        if (containsAny(lower, PREFERENCE_WORDS)) return "PREFERENCE";
        return "GENERAL";
    }

    private static boolean containsAny(String text, List<String> words) {
        for (String word : words) {
            if (text.contains(word)) return true;
        }
        return false;
    }

    private static List<ResolvedEntity> unique(List<ResolvedEntity> entities) {
        if (entities == null || entities.isEmpty()) return List.of();
        Map<String, ResolvedEntity> byId = new LinkedHashMap<>();
        for (ResolvedEntity entity : entities) {
            if (entity != null) byId.putIfAbsent(entity.entityId(), entity);
        }
        return List.copyOf(new ArrayList<>(byId.values()));
    }
}
