package org.example.ai.calibration;

import org.example.ai.calibration.ThresholdCalibrator.GoldenCase;
import org.example.ai.calibration.ThresholdCalibrator.PointReport;
import org.example.ai.impl.routing.ScoredCandidate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ThresholdCalibrator 单元测试（#40 ticket）——假 oracle 驱动，
 * 锁定 precision/recall 计数语义与"精度≥95% 约束下取最高召回"推荐策略。
 */
class ThresholdCalibratorTest {

    /** 黄金集：2 条应命中 + 2 条应判模糊 */
    private static final List<GoldenCase> CASES = List.of(
            new GoldenCase("有x3吗", "宝马-宝马X3 M", ""),
            new GoldenCase("凯美瑞", "丰田-凯美瑞", ""),
            new GoldenCase("推荐一款", null, ""),
            new GoldenCase("感觉一般", null, "")
    );

    /** oracle：各查询的候选（相似度降序） */
    private static final Map<String, List<ScoredCandidate>> HITS = Map.of(
            // x3：正确车系 0.82 高分——t≤0.82 命中
            "有x3吗", List.of(new ScoredCandidate("宝马-宝马X3 M", 0.82)),
            // 凯美瑞：正确车系 0.61——t≤0.61 命中；t≥0.7 漏配
            "凯美瑞", List.of(new ScoredCandidate("丰田-凯美瑞", 0.61)),
            // 推荐一款：只有弱候选 0.44——t≥0.5 判模糊 TN
            "推荐一款", List.of(new ScoredCandidate("比亚迪-海鸥", 0.44)),
            // 感觉一般：无候选——恒 TN
            "感觉一般", List.of()
    );

    @Test
    @DisplayName("逐点扫描：TP/FP/FN/TN 计数与 precision/recall 计算正确")
    void scanCountsMatchHandComputed() {
        List<PointReport> reports = ThresholdCalibrator.scan(CASES, HITS, List.of(0.5, 0.7, 0.9));

        // t=0.5：x3 命中(TP)，凯美瑞命中(TP)，推荐一款 0.44<0.5 无预测(TN)，感觉一般 TN
        PointReport p50 = reports.get(0);
        assertThat(p50.tp()).isEqualTo(2);
        assertThat(p50.fp()).isZero();
        assertThat(p50.fn()).isZero();
        assertThat(p50.tn()).isEqualTo(2);
        assertThat(p50.precision()).isEqualTo(1.0);
        assertThat(p50.recall()).isEqualTo(1.0);

        // t=0.7：x3 命中(TP)，凯美瑞 0.61<0.7 漏配(FN)，两个模糊 TN
        PointReport p70 = reports.get(1);
        assertThat(p70.tp()).isEqualTo(1);
        assertThat(p70.fn()).isEqualTo(1);
        assertThat(p70.precision()).isEqualTo(1.0);
        assertThat(p70.recall()).isEqualTo(0.5);

        // t=0.9：全漏配——TP=0 FN=2；无预测 → precision 空真 1.0，recall 0
        PointReport p90 = reports.get(2);
        assertThat(p90.tp()).isZero();
        assertThat(p90.fn()).isEqualTo(2);
        assertThat(p90.precision()).isEqualTo(1.0); // 空真：没做任何命中预测
        assertThat(p90.recall()).isZero();
    }

    @Test
    @DisplayName("错配语义：命中错误车系 → FP + FN 双计（错配比漏配多计一次误配）")
    void wrongSeriesHitCountsAsFpAndFn() {
        Map<String, List<ScoredCandidate>> wrongHit = Map.of(
                "凯美瑞", List.of(new ScoredCandidate("丰田-雷凌", 0.9)), // 命中了错误车系
                "感觉一般", List.of()
        );
        List<GoldenCase> cases = List.of(
                new GoldenCase("凯美瑞", "丰田-凯美瑞", ""),
                new GoldenCase("感觉一般", null, "")
        );

        PointReport r = ThresholdCalibrator.scan(cases, wrongHit, List.of(0.5)).get(0);

        assertThat(r.tp()).isZero();
        assertThat(r.fp()).isEqualTo(1);
        assertThat(r.fn()).isEqualTo(1);
        assertThat(r.precision()).isZero();
        assertThat(r.recall()).isZero();
    }

    @Test
    @DisplayName("推荐策略：precision ≥ 95% 约束下取最高召回")
    void recommendPicksMaxRecallUnderPrecisionConstraint() {
        List<PointReport> reports = ThresholdCalibrator.scan(CASES, HITS, List.of(0.5, 0.7, 0.9));

        Optional<PointReport> rec = ThresholdCalibrator.recommend(reports, 0.95);

        // 三个点 precision 都是 1.0（无错配）→ 取 recall 最高的 0.5
        assertThat(rec).isPresent();
        assertThat(rec.get().threshold()).isEqualTo(0.5);
        assertThat(rec.get().recall()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("推荐策略：违反精度约束的点被排除")
    void recommendExcludesLowPrecisionPoints() {
        // t=0.3：推荐一款的弱候选 0.44 也过线 → 模糊查询被误配 FP，precision=2/3
        List<PointReport> reports = ThresholdCalibrator.scan(CASES, HITS, List.of(0.3, 0.5));

        PointReport p30 = reports.get(0);
        assertThat(p30.fp()).isEqualTo(1);
        assertThat(p30.precision()).isLessThan(0.95);

        Optional<PointReport> rec = ThresholdCalibrator.recommend(reports, 0.95);
        assertThat(rec).isPresent();
        assertThat(rec.get().threshold()).isEqualTo(0.5); // 0.3 被精度约束排除
    }

    @Test
    @DisplayName("推荐策略：召回并列取更高阈值（宁缺勿滥）")
    void recommendTieBreaksToHigherThreshold() {
        // t=0.5 与 t=0.6 计数完全一致 → 取 0.6
        List<PointReport> reports = ThresholdCalibrator.scan(CASES, HITS, List.of(0.5, 0.6));

        Optional<PointReport> rec = ThresholdCalibrator.recommend(reports, 0.95);
        assertThat(rec).isPresent();
        assertThat(rec.get().threshold()).isEqualTo(0.6);
    }

    @Test
    @DisplayName("无满足精度约束的点 → 推荐为空")
    void recommendEmptyWhenNoPointMeetsConstraint() {
        List<PointReport> reports = ThresholdCalibrator.scan(CASES, HITS, List.of(0.3));
        assertThat(ThresholdCalibrator.recommend(reports, 0.99)).isEmpty();
    }

    @Test
    @DisplayName("报告渲染含逐点表格与推荐阈值点")
    void reportRendering() {
        List<PointReport> reports = ThresholdCalibrator.scan(CASES, HITS, List.of(0.5, 0.7));
        Optional<PointReport> rec = ThresholdCalibrator.recommend(reports, 0.95);

        String report = ThresholdCalibrator.renderReport(CASES, reports, rec, 0.95);

        assertThat(report).contains("| threshold |");
        assertThat(report).contains("| 0.50 |").contains("| 0.70 |");
        assertThat(report).contains("retrieval.threshold.series-confidence = 0.50");
        assertThat(report).contains("2 条应命中车系").contains("2 条应判模糊");
    }

    @Test
    @DisplayName("黄金集资源可加载：含规格边界用例，标注完整")
    void goldenSetLoadsFromResource() throws Exception {
        List<GoldenCase> cases = ThresholdCalibrator.loadGoldenSet();

        assertThat(cases).isNotEmpty();
        assertThat(cases).extracting(GoldenCase::query)
                .contains("有x3吗", "20万混动SUV", "那款车", "推荐一款", "感觉一般"); // #35 规格首批边界用例
        // 标注完整：每条要么有期望车系，要么显式判模糊（null）
        assertThat(cases).allSatisfy(c -> assertThat(c.query()).isNotBlank());
        assertThat(cases.stream().filter(GoldenCase::expectsAmbiguous).count())
                .as("应判模糊的用例数 > 0").isGreaterThan(0);
        assertThat(cases.stream().filter(c -> !c.expectsAmbiguous()).count())
                .as("应命中车系的用例数 > 0").isGreaterThan(0);
    }

    @Test
    @DisplayName("defaultCandidates 覆盖 0.30~0.90 步长 0.02")
    void defaultCandidateRange() {
        List<Double> candidates = ThresholdCalibrator.defaultCandidates();
        assertThat(candidates.get(0)).isEqualTo(0.30);
        assertThat(candidates.get(candidates.size() - 1)).isEqualTo(0.90);
        assertThat(candidates).hasSize(31);
    }
}