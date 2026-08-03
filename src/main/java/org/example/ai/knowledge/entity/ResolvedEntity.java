package org.example.ai.knowledge.entity;

/**
 * 已解析的实体，携带归一化 entityId 和显示名（#1 决策3）。
 */
public record ResolvedEntity(
        String entityId,
        String displayName,
        String brand,
        String series
) {
    /**
     * 车系唯一键，格式如 "比亚迪-宋PLUS"，与 askCountTracker 的 key 一致。
     */
    public String seriesKey() {
        return brand + "-" + series;
    }
}