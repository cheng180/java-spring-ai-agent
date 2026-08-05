package org.example.ai.knowledge.entity;

/**
 * 车系唯一键（"品牌-车系"，与 {@link ResolvedEntity#seriesKey()} 同格式）的静态工具。
 *
 * <p>键拆分逻辑此前散落在 VagueScorer / AskCountTracker 三处（#43 评审修复收拢），
 * 统一收口于此，避免口径漂移。</p>
 */
public final class SeriesKeys {

    private SeriesKeys() {}

    /** "比亚迪-宋PLUS DM-i" → "宋PLUS DM-i"（引导文案/日志用显示名） */
    public static String nameOf(String seriesKey) {
        int idx = seriesKey == null ? -1 : seriesKey.indexOf('-');
        return idx > 0 ? seriesKey.substring(idx + 1) : seriesKey;
    }
}