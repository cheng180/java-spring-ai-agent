package org.example.ai.knowledge.facts;

/**
 * 时序类型标记（#1 决策4）。
 * 用于区分原子事实的生命周期语义，检索时可据此做时效性排序/过滤。
 */
public enum TemporalType {

    /** 永远正确，不随时间变化（如"水在100摄氏度沸腾"、"共情是销售的基本功"） */
    ATEMPORAL,

    /** 在特定时间点变为真，此后不变（如 brand_name、model_name、"宋PLUS于2024年发布"） */
    STATIC,

    /** 可能随时间变化，需时间上下文才能准确解释（如 sale_price、stock、月度销量） */
    DYNAMIC
}