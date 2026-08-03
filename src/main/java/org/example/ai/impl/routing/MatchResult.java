package org.example.ai.impl.routing;

import java.util.List;

/**
 * VagueQueryRouter 路由结果（#13 ticket，决策9）。
 *
 * @param type          EXACT（精确车系）/ BRAND（品牌级）/ VAGUE（模糊）
 * @param carKey        命中的车系 key（EXACT 时有值）
 * @param brand         命中的品牌名（BRAND 时有值）
 * @param hotModels     该品牌热门车系列表（BRAND 时有值，按热度降序）
 * @param needsConfirm  是否需要先追问确认再进入检索
 * @param needsInference 是否需要将引导文本注入 LLM prompt
 * @param followUpText  追问文本（needsConfirm 或 needsInference 时直接使用）
 */
public record MatchResult(
        MatchType type,
        String carKey,
        String brand,
        List<String> hotModels,
        boolean needsConfirm,
        boolean needsInference,
        String followUpText
) {
    public enum MatchType { EXACT, BRAND, VAGUE }
}