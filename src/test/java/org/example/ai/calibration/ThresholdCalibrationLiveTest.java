package org.example.ai.calibration;

import org.example.ai.calibration.ThresholdCalibrator.GoldenCase;
import org.example.ai.calibration.ThresholdCalibrator.PointReport;
import org.example.ai.impl.routing.ScoredCandidate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 阈值标定实跑（#40 ticket）——真实环境（向量库 + embedding 服务）对黄金集
 * 逐点扫描候选阈值，产出可直接用于修改配置的标定报告。
 *
 * <p>只在 {@code RUN_CALIBRATION=true} 时运行：加载完整 Spring 上下文
 * （Chroma + BGE-M3 embedding，见 test 分支 application.properties），
 * 未设置时自动跳过——CI / 无环境打包不受影响。</p>
 *
 * <p>本地运行示例（先 docker compose 起 Chroma）：</p>
 * <pre>
 * RUN_CALIBRATION=true DEEPSEEK_API_KEY=&lt;key&gt; SILICONFLOW_API_KEY=&lt;key&gt; \
 *   ./mvnw test -Dtest=ThresholdCalibrationLiveTest
 * </pre>
 *
 * <p>报告输出：stdout + {@code target/calibration-report.md}。</p>
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "RUN_CALIBRATION", matches = "true")
class ThresholdCalibrationLiveTest {

    @Autowired
    private VectorStore vectorStore;

    @Test
    @DisplayName("真实环境标定：黄金集扫描候选阈值，输出 precision/recall 报告与推荐点")
    void calibrateThresholds() throws Exception {
        List<GoldenCase> cases = ThresholdCalibrator.loadGoldenSet();

        // oracle：每条黄金查询的父块候选车系（相似度降序，按车系去重取最高分）
        Map<String, List<ScoredCandidate>> hitsByQuery = new LinkedHashMap<>();
        for (GoldenCase c : cases) {
            List<ScoredCandidate> hits = parentHits(c.query());
            hitsByQuery.put(c.query(), hits);
            System.out.printf("[oracle] %s → %s%n", c.query(), hits);
        }

        List<PointReport> reports = ThresholdCalibrator.scan(
                cases, hitsByQuery, ThresholdCalibrator.defaultCandidates());
        Optional<PointReport> recommended = ThresholdCalibrator.recommend(
                reports, ThresholdCalibrator.DEFAULT_MIN_PRECISION);
        String report = ThresholdCalibrator.renderReport(
                cases, reports, recommended, ThresholdCalibrator.DEFAULT_MIN_PRECISION);

        System.out.println(report);
        Path out = Path.of("target", "calibration-report.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report);
        System.out.println("报告已写入: " + out.toAbsolutePath());

        assertThat(recommended)
                .as("应存在满足精度约束（≥95%%）的阈值点——否则先修 embedding 质量或别名覆盖率（#9）")
                .isPresent();
    }

    /** 与组装器阶段一同口径：父块相似度召回（type=车源, level=parent），topK=5 不预过滤阈值 */
    private List<ScoredCandidate> parentHits(String query) {
        FilterExpressionBuilder b = new FilterExpressionBuilder();
        Filter.Expression parentOnly = b.and(
                b.eq("type", "车源"), b.eq("level", "parent")).build();
        List<Document> docs = vectorStore.similaritySearch(
                SearchRequest.builder().query(query).topK(5).filterExpression(parentOnly).build());

        Map<String, Double> bestBySeries = new LinkedHashMap<>();
        for (Document d : docs) {
            Object sid = d.getMetadata().get("series_id");
            if (sid == null) continue;
            double sim = similarityOf(d);
            if (sim < 0) continue;
            bestBySeries.merge(sid.toString(), sim, Math::max);
        }
        return bestBySeries.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .map(e -> new ScoredCandidate(e.getKey(), e.getValue()))
                .toList();
    }

    /** 读取文档相似度（Spring AI 2.0 metadata），无分数返回 -1 */
    private static double similarityOf(Document d) {
        Object s = d.getMetadata().get("similarity");
        if (s instanceof Number n) return n.doubleValue();
        Object dist = d.getMetadata().get("distance");
        if (dist instanceof Number n) return 1.0 / (1.0 + n.doubleValue());
        return -1.0;
    }
}