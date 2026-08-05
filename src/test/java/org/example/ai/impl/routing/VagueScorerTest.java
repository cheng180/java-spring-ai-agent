package org.example.ai.impl.routing;

import org.example.ai.impl.profile.NeedSignalDetector;
import org.example.ai.knowledge.entity.ResolvedEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * VagueScorer 表驱动测试（#41 ticket，接缝 A 程度分级用例）。
 *
 * <p>三层证据判定表：结构信号（别名命中）→ 相对领先度 → 绝对相似度，
 * 边界用例与 #35 规格决策 1/2 对齐（"有x3吗"判清晰不误注入、
 * 多命中/咬得近判浅模糊、"推荐一款"类零信号判深模糊）。</p>
 */
class VagueScorerTest {

    private VagueScorer scorer;

    /** 默认阈值：与生产配置一致（0.7 / 0.6 / 0.5，gap 0.15 / 0.05） */
    @BeforeEach
    void setUp() {
        scorer = new VagueScorer(new NeedSignalDetector(), 0.7, 0.6, 0.5, 0.15, 0.05);
    }

    // ---- 第一层：结构信号（别名命中） ----

    @Test
    @DisplayName("单别名命中（\"有x3吗\"类）→ 档 0 清晰，不误注入引导")
    void singleAliasHitIsClear() {
        VagueAssessment a = scorer.score("有x3吗",
                List.of(entity("宝马", "宝马X3 M")), List.of());

        assertThat(a.tier()).isEqualTo(VagueAssessment.TIER_CLEAR);
        assertThat(a.confidence()).isGreaterThanOrEqualTo(0.7);
        assertThat(a.candidates()).containsExactly("宝马-宝马X3 M"); // 车系键，显示名由引导层剥离
    }

    @Test
    @DisplayName("别名多命中 → 档 1 浅模糊，候选集 = 命中车系（无一票否决）")
    void multipleAliasHitsAreLightAmbiguous() {
        VagueAssessment a = scorer.score("秦系列怎么选",
                List.of(entity("比亚迪", "秦L"), entity("比亚迪", "秦PLUS DM-i")), List.of());

        assertThat(a.tier()).isEqualTo(VagueAssessment.TIER_LIGHT);
        assertThat(a.candidates()).containsExactly("比亚迪-秦L", "比亚迪-秦PLUS DM-i");
    }

    // ---- 第二层：相对领先度（无别名、无需求信号） ----

    @Test
    @DisplayName("明确领先（top1−top2 ≥ 0.15）→ 档 0 清晰，领先明确时不看绝对值")
    void clearLeadIsClear() {
        VagueAssessment a = scorer.score("那辆车空间怎么样", List.of(), List.of(
                new ScoredCandidate("比亚迪-宋PLUS DM-i", 0.62),
                new ScoredCandidate("比亚迪-唐DM-i", 0.40)));

        // top1=0.62 < 0.7 但领先 0.22 明确 → 清晰（抗分数整体漂移）
        assertThat(a.tier()).isEqualTo(VagueAssessment.TIER_CLEAR);
        assertThat(a.candidates()).containsExactly("比亚迪-宋PLUS DM-i");
    }

    @Test
    @DisplayName("候选咬得近（top1−top2 ≤ 0.05）→ 档 1 浅模糊，选项限 top 1-2")
    void closeCandidatesAreLightAmbiguous() {
        VagueAssessment a = scorer.score("有什么好开的", List.of(), List.of(
                new ScoredCandidate("比亚迪-汉EV", 0.66),
                new ScoredCandidate("比亚迪-海豹", 0.64),
                new ScoredCandidate("比亚迪-海鸥", 0.60)));

        assertThat(a.tier()).isEqualTo(VagueAssessment.TIER_LIGHT);
        assertThat(a.candidates()).containsExactly("比亚迪-汉EV", "比亚迪-海豹"); // 只取 top2
    }

    @Test
    @DisplayName("单候选且相似度达标 → 领先 = top1，判清晰")
    void singleCandidateWithDecentScoreIsClear() {
        VagueAssessment a = scorer.score("空间大点的", List.of(), List.of(
                new ScoredCandidate("大众-途观L", 0.58)));

        assertThat(a.tier()).isEqualTo(VagueAssessment.TIER_CLEAR);
    }

    // ---- 需求信号 → 中模糊 ----

    @Test
    @DisplayName("有需求信号无锚点（\"20万混动SUV\"）→ 档 2 中模糊，候选取相似度达标者")
    void needSignalsAreMediumAmbiguous() {
        VagueAssessment a = scorer.score("20万混动SUV", List.of(), List.of(
                new ScoredCandidate("比亚迪-宋PLUS DM-i", 0.71),
                new ScoredCandidate("吉利-吉利星越L", 0.45)));

        assertThat(a.tier()).isEqualTo(VagueAssessment.TIER_MEDIUM);
        assertThat(a.signals()).containsKeys("budget", "energy", "carType");
        // 候选只保留相似度 ≥ deep(0.5) 的（吉利星越L 0.45 被滤掉）
        assertThat(a.candidates()).containsExactly("比亚迪-宋PLUS DM-i");
    }

    @Test
    @DisplayName("有需求信号但检索全落空 → 档 2 中模糊，空候选（补问最缺维度）")
    void needSignalsWithoutCandidatesAreMedium() {
        VagueAssessment a = scorer.score("预算15万", List.of(), List.of());

        assertThat(a.tier()).isEqualTo(VagueAssessment.TIER_MEDIUM);
        assertThat(a.candidates()).isEmpty();
        assertThat(a.signals()).containsKey("budget");
    }

    // ---- 零信号 → 深模糊 ----

    @Test
    @DisplayName("零信号零证据（\"推荐一款\"类）→ 档 3 深模糊")
    void zeroSignalIsDeepAmbiguous() {
        VagueAssessment a = scorer.score("推荐一款", List.of(), List.of());

        assertThat(a.tier()).isEqualTo(VagueAssessment.TIER_DEEP);
        assertThat(a.candidates()).isEmpty();
        assertThat(a.confidence()).isLessThan(0.3);
    }

    @Test
    @DisplayName("零信号 + 检索弱（top1 < 0.5）→ 档 3 深模糊")
    void zeroSignalWithWeakRetrievalIsDeep() {
        VagueAssessment a = scorer.score("随便看看", List.of(), List.of(
                new ScoredCandidate("比亚迪-海鸥", 0.44),
                new ScoredCandidate("大众-朗逸", 0.30)));

        assertThat(a.tier()).isEqualTo(VagueAssessment.TIER_DEEP);
    }

    // ---- 序约束与配置边界 ----

    @Test
    @DisplayName("阈值违反序约束（clear < gray）→ 夹取保持 clear≥gray≥deep")
    void thresholdsAreClampedToOrderConstraint() {
        // clear=0.5 < gray=0.6 → gray 夹到 0.5；多命中置信度随之夹取
        VagueScorer misconfigured = new VagueScorer(new NeedSignalDetector(), 0.5, 0.6, 0.5, 0.15, 0.05);
        VagueAssessment a = misconfigured.score("秦系列",
                List.of(entity("比亚迪", "秦L"), entity("比亚迪", "秦PLUS DM-i")), List.of());

        assertThat(a.confidence()).isLessThanOrEqualTo(0.5);
    }

    @Test
    @DisplayName("null 消息/空候选不抛异常——fail-safe 降级为深模糊")
    void nullSafeScoring() {
        VagueAssessment a = scorer.score("", null, null);
        assertThat(a.tier()).isEqualTo(VagueAssessment.TIER_DEEP);
    }

    // ---- helpers ----

    private static ResolvedEntity entity(String brand, String series) {
        return new ResolvedEntity("entity:" + brand + ":" + series,
                brand + "-" + series, brand, series);
    }
}