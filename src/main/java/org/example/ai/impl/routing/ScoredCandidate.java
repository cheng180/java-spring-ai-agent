package org.example.ai.impl.routing;

/**
 * 带相似度分数的候选车系（#41 ticket，#35 规格决策 2 第二/三层证据载体）。
 *
 * <p>由上下文组装器阶段一父块召回按车系去重（同车系取最高分）、降序排列后，
 * 交给 {@link VagueScorer} 计算相对领先度（top1−top2）与绝对相似度证据。</p>
 *
 * @param seriesKey  车系唯一键（"品牌-车系"，与 AskCountTracker key 一致）
 * @param similarity BGE-M3 相似度（0~1；无分数时不进入候选）
 */
public record ScoredCandidate(String seriesKey, double similarity) {}