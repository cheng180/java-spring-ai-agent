package org.example.ai.calibration;

import org.example.ai.calibration.ThresholdCalibrator.GoldenCase;
import org.example.ai.calibration.ThresholdCalibrator.PointReport;
import org.example.ai.impl.context.RetrievalContextAssembler;
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

    /**
     * 与生产阶段一父块召回同口径：复用组装器的 {@code scoredCandidates}
     * （按车系去重取最高分、无分数文档不计入），标定 oracle 不漂移。
     *
     * <p>与生产的两处已知差异（均为标定所需）：topK 同为 3，但<b>不施加阈值预过滤</b>——
     * 被标定的正是阈值本身，预过滤会截断低分证据；BM25 单边命中无相似度分数，
     * 天然不进入标定（阈值只治理 BGE 向量路）。</p>
     */
    private List<ScoredCandidate> parentHits(String query) {
        FilterExpressionBuilder b = new FilterExpressionBuilder();
        Filter.Expression parentOnly = b.and(
                b.eq("type", "车源"), b.eq("level", "parent")).build();
        List<Document> docs = vectorStore.similaritySearch(
                SearchRequest.builder().query(query).topK(3).filterExpression(parentOnly).build());
        return RetrievalContextAssembler.scoredCandidates(docs);
    }
}