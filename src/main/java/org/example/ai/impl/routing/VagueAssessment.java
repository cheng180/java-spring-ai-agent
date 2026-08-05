package org.example.ai.impl.routing;

import java.util.List;
import java.util.Map;

/**
 * 模糊程度评定结果（#41 ticket，#35 规格决策 1/2）。
 *
 * <p>每条消息计算连续置信度（0~1）并映射四档：
 * 0=清晰（直接答）、1=浅模糊（候选 2~3，给选项确认）、
 * 2=中模糊（有需求信号锚不定，能匹配给 1-2 款、不能则补问）、
 * 3=深模糊（零信号，一步步引导）。
 * 连续分数随对话日志入档，供阈值标定（#40）。</p>
 *
 * @param tier       模糊档位（{@link #TIER_CLEAR}~{@link #TIER_DEEP}）
 * @param confidence 连续置信度 0~1，越高越清晰
 * @param candidates 候选车系显示名（档 1 选项引导用；其他档可为空）
 * @param signals    需求信号（budget/use/carType/energy → 命中表述）
 */
public record VagueAssessment(int tier, double confidence, List<String> candidates, Map<String, String> signals) {

    /** 清晰：直接回答，不注入引导 */
    public static final int TIER_CLEAR = 0;
    /** 浅模糊：候选 2~3 个（别名多命中或分数咬得近）→ 给热度 top 1-2 选项确认 */
    public static final int TIER_LIGHT = 1;
    /** 中模糊：有需求信号但锚不定 → 能匹配给 1-2 款，不能则补问最缺的一个维度 */
    public static final int TIER_MEDIUM = 2;
    /** 深模糊：零信号 → 一步步引导，精选 1-2 款起点 + 轻问题 */
    public static final int TIER_DEEP = 3;
}