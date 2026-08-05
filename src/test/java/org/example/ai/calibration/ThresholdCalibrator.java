package org.example.ai.calibration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.ai.impl.routing.ScoredCandidate;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 阈值标定工具（#40 ticket，#35 决策 9）——离线纯逻辑：对候选阈值区间逐点扫描，
 * 按黄金集标注输出 precision/recall，并按既定策略给出推荐阈值点。
 *
 * <p>判定语义（对每条黄金用例、每个候选阈值 t）：
 * oracle 给出该查询的父块候选车系（相似度降序），相似度 ≥ t 的首个候选记为"预测命中"。</p>
 * <ul>
 *   <li>期望命中车系 X：预测 == X → TP；无预测 → FN（漏配）；预测 ≠ X → FP+FN（错配）；</li>
 *   <li>期望判模糊：无预测 → TN；有预测 → FP（误配）。</li>
 * </ul>
 *
 * <p>推荐策略（销售场景错配比漏配代价高）：精度 ≥ 95% 约束下取最高召回的点；
 * 召回并列取更高阈值（宁缺勿滥，fail-safe 方向）。</p>
 *
 * <p>真实环境运行入口见 {@code ThresholdCalibrationLiveTest}
 * （向量库 + embedding 服务，RUN_CALIBRATION=true 触发）。</p>
 */
public final class ThresholdCalibrator {

    /** 推荐策略的精度下限（#35 决策 9：precision ≥ 95%） */
    public static final double DEFAULT_MIN_PRECISION = 0.95;

    private ThresholdCalibrator() {}

    /** 黄金用例：expectedSeries 为 null = 应判模糊（不应高置信命中任何车系） */
    public record GoldenCase(String query, String expectedSeries, String note) {
        public boolean expectsAmbiguous() {
            return expectedSeries == null;
        }
    }

    /** 黄金集资源路径（受版本控制，含期望结果标注） */
    public static final String GOLDEN_SET_RESOURCE = "/calibration/golden-set.json";

    /** 从 classpath 加载黄金集（query / expectedSeries / note） */
    public static List<GoldenCase> loadGoldenSet() throws IOException {
        try (InputStream in = ThresholdCalibrator.class.getResourceAsStream(GOLDEN_SET_RESOURCE)) {
            if (in == null) throw new IOException("黄金集资源缺失: " + GOLDEN_SET_RESOURCE);
            JsonNode root = new ObjectMapper().readTree(in);
            List<GoldenCase> cases = new ArrayList<>();
            for (JsonNode node : root) {
                String query = node.path("query").asText();
                JsonNode expected = node.get("expectedSeries");
                String expectedSeries = expected == null || expected.isNull() ? null : expected.asText();
                cases.add(new GoldenCase(query, expectedSeries, node.path("note").asText("")));
            }
            return cases;
        }
    }

    /** 单个候选阈值点的扫描结果 */
    public record PointReport(double threshold, int tp, int fp, int fn, int tn,
                              double precision, double recall) {}

    /** 默认候选阈值区间：0.30 → 0.90，步长 0.02 */
    public static List<Double> defaultCandidates() {
        List<Double> candidates = new ArrayList<>();
        for (double t = 0.30; t <= 0.9001; t += 0.02) {
            candidates.add(Math.round(t * 100.0) / 100.0);
        }
        return candidates;
    }

    /**
     * 对候选阈值逐点扫描，输出 precision/recall 报告。
     *
     * @param cases              黄金集
     * @param hitsByQuery        oracle 输出：查询 → 父块候选车系（相似度降序）
     * @param candidateThresholds 候选阈值点（任意顺序，报告按输入序输出）
     */
    public static List<PointReport> scan(List<GoldenCase> cases,
                                         Map<String, List<ScoredCandidate>> hitsByQuery,
                                         List<Double> candidateThresholds) {
        List<PointReport> reports = new ArrayList<>();
        for (double t : candidateThresholds) {
            int tp = 0, fp = 0, fn = 0, tn = 0;
            for (GoldenCase c : cases) {
                List<ScoredCandidate> hits = hitsByQuery.getOrDefault(c.query(), List.of());
                ScoredCandidate predicted = hits.stream()
                        .filter(h -> h.similarity() >= t)
                        .findFirst().orElse(null);
                if (c.expectsAmbiguous()) {
                    if (predicted == null) tn++;
                    else fp++;                       // 误配：模糊查询被当成车系命中
                } else if (predicted != null && c.expectedSeries().equals(predicted.seriesKey())) {
                    tp++;
                } else {
                    fn++;                            // 漏配：低于阈值，或错配未命中正确车系
                    if (predicted != null) fp++;     // 错配：命中了错误车系（另计一次误配）
                }
            }
            double precision = (tp + fp) == 0 ? 1.0 : (double) tp / (tp + fp);
            double recall = (tp + fn) == 0 ? 1.0 : (double) tp / (tp + fn);
            reports.add(new PointReport(t, tp, fp, fn, tn, precision, recall));
        }
        return reports;
    }

    /**
     * 推荐阈值点：precision ≥ minPrecision 的点中取最高 recall；
     * recall 并列取更高阈值（宁缺勿滥）。无满足约束的点时返回空。
     */
    public static Optional<PointReport> recommend(List<PointReport> reports, double minPrecision) {
        return reports.stream()
                .filter(r -> r.precision() >= minPrecision)
                .max(Comparator.comparingDouble(PointReport::recall)
                        .thenComparingDouble(PointReport::threshold));
    }

    /** 渲染 Markdown 标定报告（含逐点数据与推荐阈值点） */
    public static String renderReport(List<GoldenCase> cases, List<PointReport> reports,
                                      Optional<PointReport> recommended, double minPrecision) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 相似度阈值标定报告（#40）\n\n");
        sb.append("- 黄金集：").append(cases.size()).append(" 条（");
        long positives = cases.stream().filter(c -> !c.expectsAmbiguous()).count();
        sb.append(positives).append(" 条应命中车系 / ")
          .append(cases.size() - positives).append(" 条应判模糊）\n");
        sb.append("- 策略：precision ≥ ")
          .append(String.format(Locale.ROOT, "%.0f%%", minPrecision * 100))
          .append(" 约束下取最高召回；召回并列取更高阈值（宁缺勿滥）\n\n");
        sb.append("| threshold | TP | FP | FN | TN | precision | recall |\n");
        sb.append("|-----------|----|----|----|----|-----------|--------|\n");
        for (PointReport r : reports) {
            sb.append(String.format(Locale.ROOT, "| %.2f | %d | %d | %d | %d | %.1f%% | %.1f%% |%n",
                    r.threshold(), r.tp(), r.fp(), r.fn(), r.tn(),
                    r.precision() * 100, r.recall() * 100));
        }
        sb.append("\n## 推荐阈值点\n\n");
        if (recommended.isPresent()) {
            PointReport r = recommended.get();
            sb.append(String.format(Locale.ROOT,
                    "`retrieval.threshold.series-confidence = %.2f`（precision=%.1f%%, recall=%.1f%%）%n",
                    r.threshold(), r.precision() * 100, r.recall() * 100));
            sb.append("\n修改 `application.properties` 对应配置即生效；");
            sb.append("注意保持序约束 0.7≥0.6≥0.5（vague.confidence.clear ≥ 本阈值 ≥ retrieval.threshold.rag-fallback）。\n");
        } else {
            sb.append("无满足精度约束的阈值点——检查黄金集标注或 embedding 质量（别名覆盖率见 #9）。\n");
        }
        return sb.toString();
    }
}