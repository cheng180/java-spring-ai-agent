package org.example.ai.impl.routing;

import org.example.ai.impl.profile.NeedSignalDetector;
import org.example.ai.knowledge.entity.ResolvedEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 模糊程度打分器（#41 ticket，#35 规格决策 1/2）——与 {@link QueryLevelClassifier}
 * 并列进理解层，由 {@code RetrievalContextAssembler} 编排。
 *
 * <p>三层置信证据，优先级递减：</p>
 * <ol>
 *   <li><b>结构信号</b>——实体别名命中：单命中→清晰；多命中→浅模糊候选集
 *       （多命中只给候选，不搞一票否决）；</li>
 *   <li><b>相对领先度</b>——top1 与 top2 相似度差距，差距大即信心高，
 *       对分数整体漂移免疫（领先明确时不看绝对值）；</li>
 *   <li><b>绝对相似度</b>——仅在无结构信号、无明确领先时裁决灰色地带，
 *       0.7/0.6/0.5 属本层。</li>
 * </ol>
 *
 * <p>档位语义：0=清晰（直接答）；1=浅模糊（给选项确认）；2=中模糊（有需求信号锚不定，
 * 能匹配给 1-2 款、不能则补问最缺维度）；3=深模糊（零信号，一步步引导）。
 * 程度打分不短路、不产出模板回答——引导文案由
 * {@code VagueGuidanceBuilder} 生成，LLM 始终在环（历史教训吸收）。</p>
 *
 * <p>档位边界外置配置（#35 决策 9）：
 * 0/1 边界 = {@code vague.confidence.clear}（0.7）；
 * 灰色地带上沿 = {@code retrieval.threshold.series-confidence}（0.6）；
 * 2/3 边界 = {@code retrieval.threshold.rag-fallback}（0.5）。
 * 三阈值强制保持 0.7≥0.6≥0.5 序约束——违反时夹取并告警（fail-safe，不阻断启动）。</p>
 */
@Component
public class VagueScorer {

    private static final Logger log = LoggerFactory.getLogger(VagueScorer.class);

    /** 单别名命中的结构置信度——结构信号本身就够强，不依赖相似度证据 */
    static final double SINGLE_ALIAS_CONFIDENCE = 0.9;

    private final NeedSignalDetector signalDetector;
    private final double clearConfidence;
    private final double grayConfidence;
    private final double deepConfidence;
    private final double clearLeadGap;
    private final double closeLeadGap;

    public VagueScorer(NeedSignalDetector signalDetector,
                       @Value("${vague.confidence.clear:0.7}") double clearConfidence,
                       @Value("${retrieval.threshold.series-confidence:0.6}") double grayConfidence,
                       @Value("${retrieval.threshold.rag-fallback:0.5}") double deepConfidence,
                       @Value("${vague.lead.clear-gap:0.15}") double clearLeadGap,
                       @Value("${vague.lead.close-gap:0.05}") double closeLeadGap) {
        this.signalDetector = signalDetector;
        // 强制保持序约束 clear ≥ gray ≥ deep（#35 决策 9）——违反夹取并告警
        double gray = Math.min(grayConfidence, clearConfidence);
        double deep = Math.min(deepConfidence, gray);
        if (gray != grayConfidence || deep != deepConfidence) {
            log.warn("模糊阈值违反序约束（clear≥gray≥deep），已夹取：clear={}, gray={}→{}, deep={}→{}",
                    clearConfidence, grayConfidence, gray, deepConfidence, deep);
        }
        this.clearConfidence = clearConfidence;
        this.grayConfidence = gray;
        this.deepConfidence = deep;
        this.clearLeadGap = clearLeadGap;
        this.closeLeadGap = closeLeadGap;
    }

    /**
     * 计算模糊程度评定。
     *
     * <p>调用方约定：BRAND/FAMILY/SERIES 级别传空候选（这些路径不做父块相似度召回，
     * 程度仅由结构信号/需求信号驱动，只记日志不注入引导）；
     * UNRESTRICTED 级别传阶段一父块召回的候选（按车系去重、相似度降序）。</p>
     *
     * @param userMessage   用户消息
     * @param matchedSeries EntityResolver 别名命中（结构信号，可空）
     * @param candidates    带相似度的候选车系（可空）
     */
    public VagueAssessment score(String userMessage, List<ResolvedEntity> matchedSeries,
                                 List<ScoredCandidate> candidates) {
        Map<String, String> signals = signalDetector.detect(userMessage);

        // ---- 第一层：结构信号（实体别名命中） ----
        if (matchedSeries != null && !matchedSeries.isEmpty()) {
            List<String> keys = matchedSeries.stream().map(ResolvedEntity::seriesKey).toList();
            if (matchedSeries.size() == 1) {
                // 单命中 → 清晰（"有x3吗"类边界用例不误注入引导）
                return new VagueAssessment(VagueAssessment.TIER_CLEAR,
                        SINGLE_ALIAS_CONFIDENCE, keys, signals);
            }
            // 多命中 → 浅模糊候选集；置信度记灰色地带上沿（可标定）
            return new VagueAssessment(VagueAssessment.TIER_LIGHT,
                    grayConfidence, keys, signals);
        }

        // ---- 无结构信号：第二层相对领先度 + 第三层绝对相似度 ----
        int n = candidates == null ? 0 : candidates.size();
        double top1 = n >= 1 ? candidates.get(0).similarity() : 0.0;
        double top2 = n >= 2 ? candidates.get(1).similarity() : 0.0;
        double lead = n >= 2 ? top1 - top2 : top1; // 单候选 = 最大领先

        // 有需求信号但无别名锚点 → 中模糊（能匹配给 1-2 款，不能则补问最缺维度）
        if (!signals.isEmpty()) {
            double conf = 0.3 + 0.4 * clamp01((top1 - deepConfidence) / span());
            List<String> keys = n == 0 ? List.of()
                    : candidates.stream()
                        .filter(c -> c.similarity() >= deepConfidence)
                        .limit(2)
                        .map(ScoredCandidate::seriesKey)
                        .toList();
            return new VagueAssessment(VagueAssessment.TIER_MEDIUM, round2(conf), keys, signals);
        }

        // 零信号 + 明确领先（差距 ≥ clearGap）→ 清晰；领先明确时不看绝对值（抗整体漂移）
        if (top1 >= deepConfidence && lead >= clearLeadGap) {
            return new VagueAssessment(VagueAssessment.TIER_CLEAR, round2(top1),
                    n >= 1 ? List.of(candidates.get(0).seriesKey()) : List.of(),
                    signals);
        }

        // 零信号 + 候选咬得近（差距 ≤ closeGap）→ 浅模糊，给 top 1-2 选项确认
        if (n >= 2 && lead <= closeLeadGap && top1 >= deepConfidence) {
            List<String> keys = candidates.stream()
                    .filter(c -> c.similarity() >= deepConfidence)
                    .limit(2)
                    .map(ScoredCandidate::seriesKey)
                    .toList();
            return new VagueAssessment(VagueAssessment.TIER_LIGHT,
                    round2((top1 + top2) / 2), keys, signals);
        }

        // 深模糊：零信号且无强检索证据（绝对相似度仅在灰色地带裁决，0.7/0.6/0.5 属本层）
        double conf = Math.max(0.05, round2(Math.min(top1, deepConfidence) / 2));
        return new VagueAssessment(VagueAssessment.TIER_DEEP, conf, List.of(), signals);
    }

    private double span() {
        double s = clearConfidence - deepConfidence;
        return s <= 0 ? 1.0 : s;
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
