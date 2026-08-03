package org.example.ai.knowledge.facts;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LlmFactExtractor 解析逻辑单元测试（不调 LLM）。
 */
class LlmFactExtractorParseTest {

    /**
     * 创建无需 ChatClient 的测试实例（仅测试 parseResponse）。
     * 实际 LLM 调用在集成测试中验证。
     */
    private final LlmFactExtractor extractor = new LlmFactExtractor(null);

    @Test
    @DisplayName("解析标准 LLM JSON 响应")
    void parseStandardLlmResponse() {
        String response = """
            [
              {"content": "比亚迪宋PLUS DM-i于2024年发布", "temporal_type": "STATIC"},
              {"content": "共情是销售的基本功", "temporal_type": "ATEMPORAL"},
              {"content": "当前全款售价为15.88万元", "temporal_type": "DYNAMIC"}
            ]""";

        List<AtomicFact> facts = extractor.parseResponse(response, "test.md", "entity:test");
        assertThat(facts).hasSize(3);

        assertThat(facts.get(0).getTemporalType()).isEqualTo(TemporalType.STATIC);
        assertThat(facts.get(0).getContent()).contains("2024年发布");

        assertThat(facts.get(1).getTemporalType()).isEqualTo(TemporalType.ATEMPORAL);
        assertThat(facts.get(2).getTemporalType()).isEqualTo(TemporalType.DYNAMIC);

        // 每条都有 hash
        facts.forEach(f -> assertThat(f.getSourceHash()).hasSize(64));
    }

    @Test
    @DisplayName("LLM 响应前后有说明文字时提取 JSON 数组")
    void parseResponseWithSurroundingText() {
        String response = """
            好的，以下是提取的原子事实：

            [
              {"content": "理想L6综合续航1390km", "temporal_type": "STATIC"},
              {"content": "推荐先了解客户预算再推荐车型", "temporal_type": "ATEMPORAL"}
            ]

            共2条事实。""";

        List<AtomicFact> facts = extractor.parseResponse(response, "test.md", "entity:test");
        assertThat(facts).hasSize(2);
    }

    @Test
    @DisplayName("LLM 返回无效 JSON 时降级为单条事实")
    void parseInvalidJsonFallsBack() {
        String response = "这是关于比亚迪的详细介绍，宋PLUS是一款很不错的SUV...";

        List<AtomicFact> facts = extractor.parseResponse(response, "test.md", "entity:fallback");
        assertThat(facts).hasSize(1);
        assertThat(facts.get(0).getFactId()).isEqualTo("entity:fallback-000");
        assertThat(facts.get(0).getTemporalType()).isEqualTo(TemporalType.STATIC);
    }

    @Test
    @DisplayName("空响应降级")
    void emptyResponseFallsBack() {
        List<AtomicFact> facts = extractor.parseResponse("", "test.md", "entity:empty");
        assertThat(facts).hasSize(1);
    }

    @Test
    @DisplayName("factId 序号递增")
    void factIdIncrementsSequentially() {
        String response = """
            [
              {"content": "事实A", "temporal_type": "STATIC"},
              {"content": "事实B", "temporal_type": "STATIC"},
              {"content": "事实C", "temporal_type": "STATIC"}
            ]""";

        List<AtomicFact> facts = extractor.parseResponse(response, "doc.md", "entity:seq");
        assertThat(facts).hasSize(3);
        assertThat(facts.get(0).getFactId()).isEqualTo("entity:seq-000");
        assertThat(facts.get(1).getFactId()).isEqualTo("entity:seq-001");
        assertThat(facts.get(2).getFactId()).isEqualTo("entity:seq-002");
    }
}