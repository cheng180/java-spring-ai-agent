package org.example.ai.impl.routing;

import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 闲聊门（#29 ticket）——黑名单制，反转判定方向。
 *
 * <p>旧设计是白名单制：穷举"什么是买车"（话题词表/关键词表/实体别名），
 * 全部落空才判闲聊——"我想买x3"这类未知话术的购车意图会被漏判为闲聊。
 * 白名单永远追不上话术变化，结构性不可修。</p>
 *
 * <p>新设计只穷举"什么是明确闲聊"（黑名单，小规模、稳定）：
 * 命中 → 闲聊分支（3 轮送客计数等既有行为不变）；
 * <b>其余一切消息默认进业务管线</b>——把不确定性导向业务侧，
 * 漏网的最坏代价是多跑一次廉价检索，而不是丢销售线索。</p>
 *
 * <p>黑名单分两类：</p>
 * <ul>
 *   <li>话题类：词本身即闲聊话题（天气/笑话/问身份），子串命中即判。</li>
 *   <li>寒暄类：问候/感谢/道别，常作业务句前缀（"你好，我想买x3"），
 *       仅当消息几乎只有寒暄（有效长度 ≤ 命中词长 + {@link #PHATIC_TAIL_BUDGET}）时才判。</li>
 * </ul>
 */
@Component
public class IdleChatGate {

    /** 话题类黑名单：命中即闲聊 */
    private static final Set<String> TOPIC_PATTERNS = Set.of(
            "天气", "笑话",
            "你是谁", "你叫什么", "你是机器人", "你是真人", "你是ai"
    );

    /** 寒暄类黑名单：需通过长度守卫（防"你好，我想买x3"误判） */
    private static final Set<String> PHATIC_PATTERNS = Set.of(
            // 问候
            "你好", "您好", "在吗", "嗨", "哈喽", "hello", "hi",
            "早上好", "下午好", "晚上好",
            // 感谢
            "谢谢", "多谢", "感谢", "thanks", "thx",
            // 道别
            "再见", "拜拜", "回见", "先这样", "下次再聊", "不用了"
    );

    /** 寒暄类允许的最大额外字符数（去掉空白/标点后的有效长度口径） */
    private static final int PHATIC_TAIL_BUDGET = 4;

    /**
     * 是否明确闲聊。
     *
     * @param message 用户消息
     * @return true=命中闲聊黑名单；false=默认业务管线（含未知话术）
     */
    public boolean isIdleChat(String message) {
        if (message == null || message.isBlank()) return true;

        String lower = message.toLowerCase();

        // 话题类：词本身即话题，直接命中
        for (String p : TOPIC_PATTERNS) {
            if (lower.contains(p)) return true;
        }

        // 寒暄类：仅当消息几乎只有寒暄时命中
        int effectiveLen = stripDecorations(lower).length();
        for (String p : PHATIC_PATTERNS) {
            if (lower.contains(p) && effectiveLen <= p.length() + PHATIC_TAIL_BUDGET) {
                return true;
            }
        }
        return false;
    }

    /** 去掉空白与常见标点，得到有效内容长度 */
    private static String stripDecorations(String s) {
        return s.replaceAll("[\\s，。！？,.!?~～、…]+", "");
    }
}