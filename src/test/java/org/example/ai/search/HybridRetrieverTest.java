package org.example.ai.search;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HybridRetriever 核心逻辑单元测试（RRF 融合、去重、排序）。
 */
class HybridRetrieverTest {

    private HybridRetriever retriever;

    @BeforeEach
    void setUp() {
        // 不注入真实依赖，直接测试 RRF 逻辑
        retriever = null; // 仅测试静态方法
    }

    // ---- RRF 融合 ----

    @Test
    @DisplayName("RRF: 两路有一致结果 → 融合后分数更高且排名靠前")
    void rrfBoostsCommonResults() {
        // BM25 结果
        var bm25 = List.of(
                new HybridRetriever.RankedDoc("d1", "比亚迪宋PLUS DM-i 混动", 5.0, null),
                new HybridRetriever.RankedDoc("d2", "特斯拉Model Y 纯电", 3.0, null),
                new HybridRetriever.RankedDoc("d3", "理想L6 增程", 1.0, null)
        );
        // BGE-M3 结果（d1 也在其中，应该得到 RRF boost）
        var bge = List.of(
                new HybridRetriever.RankedDoc("d4", "海鸥 小型纯电车", 0.9, null),
                new HybridRetriever.RankedDoc("d1", "比亚迪宋PLUS DM-i 混动", 0.8, null), // 注意：文本同 d1
                new HybridRetriever.RankedDoc("d5", "汉EV 纯电", 0.7, null)
        );

        List<HybridRetriever.RankedDoc> fused = new HybridRetriever(null, null)
                .rrfFuse(bm25, bge, 5);

        assertThat(fused).isNotEmpty();
        // d1 出现在两路 rank1(0) 和 rank2(1) → RRF = 1/(60+1)+1/(60+2) ≈ 0.0164+0.0161 ≈ 0.0326
        // 找 d1（文本包含"比亚迪宋PLUS"）
        var d1Result = fused.stream()
                .filter(r -> r.text().contains("比亚迪宋PLUS"))
                .findFirst();
        assertThat(d1Result).isPresent();
        // d1 应该排第一（分数最高，因为两路都出现）
        assertThat(fused.get(0).text()).contains("比亚迪宋PLUS");
    }

    @Test
    @DisplayName("RRF: 单路结果 → 全部保留，按 RRF rank 分排序")
    void rrfSinglePathResults() {
        var bm25 = List.of(
                new HybridRetriever.RankedDoc("a", "宝马X3 M", 10.0, null),
                new HybridRetriever.RankedDoc("b", "奥迪A4L", 5.0, null),
                new HybridRetriever.RankedDoc("c", "奔驰C级", 2.0, null)
        );
        var empty = List.<HybridRetriever.RankedDoc>of();

        List<HybridRetriever.RankedDoc> fused = new HybridRetriever(null, null)
                .rrfFuse(bm25, empty, 3);

        assertThat(fused).hasSize(3);
        // rank 高（位置靠前）的保持在前
        assertThat(fused.get(0).text()).contains("宝马X3");
        assertThat(fused.get(1).text()).contains("奥迪A4L");
    }

    @Test
    @DisplayName("RRF: topK 限制 → 返回不超过 topK 条")
    void rrfLimitsTopK() {
        var bm25 = List.of(
                new HybridRetriever.RankedDoc("1", "doc1", 10.0, null),
                new HybridRetriever.RankedDoc("2", "doc2", 9.0, null),
                new HybridRetriever.RankedDoc("3", "doc3", 8.0, null),
                new HybridRetriever.RankedDoc("4", "doc4", 7.0, null),
                new HybridRetriever.RankedDoc("5", "doc5", 6.0, null)
        );
        var bge = List.of(
                new HybridRetriever.RankedDoc("6", "doc6", 0.9, null),
                new HybridRetriever.RankedDoc("7", "doc7", 0.8, null)
        );

        List<HybridRetriever.RankedDoc> fused = new HybridRetriever(null, null)
                .rrfFuse(bm25, bge, 3);

        assertThat(fused).hasSize(3);
    }

    @Test
    @DisplayName("RRF: 空输入 → 返回空列表")
    void rrfEmptyInputReturnsEmpty() {
        var empty = List.<HybridRetriever.RankedDoc>of();
        List<HybridRetriever.RankedDoc> fused = new HybridRetriever(null, null)
                .rrfFuse(empty, empty, 5);
        assertThat(fused).isEmpty();
    }

    @Test
    @DisplayName("RRF: 文本指纹去重 → 相同内容的两路文档只保留一条")
    void rrfDeduplicatesByText() {
        // 两路中出现相同文本的文档
        var bm25 = List.of(
                new HybridRetriever.RankedDoc("sk1", "本田思域 混动轿车 省油", 3.0, null),
                new HybridRetriever.RankedDoc("sk2", "丰田卡罗拉 燃油车", 2.0, null)
        );
        var bge = List.of(
                new HybridRetriever.RankedDoc("vec1", "本田思域 混动轿车 省油", 0.9, null) // 同文本！
        );

        List<HybridRetriever.RankedDoc> fused = new HybridRetriever(null, null)
                .rrfFuse(bm25, bge, 5);

        // 应该只有 2 条结果（思域去重 + 卡罗拉）
        assertThat(fused).hasSize(2);
        // "本田思域" 的 RRF 分数应该高于单路的 "卡罗拉"
        assertThat(fused.get(0).text()).contains("本田思域");
    }

    // ---- 指纹 ----

    @Test
    @DisplayName("相同文本 → 相同指纹")
    void fingerprintDeterministic() {
        String fp1 = HybridRetriever.fingerprint("比亚迪宋PLUS DM-i混动旗舰型");
        String fp2 = HybridRetriever.fingerprint("比亚迪宋PLUS DM-i混动旗舰型");
        assertThat(fp1).isEqualTo(fp2);
    }

    @Test
    @DisplayName("不同文本 → 不同指纹")
    void fingerprintDifferentForDifferentText() {
        String fp1 = HybridRetriever.fingerprint("比亚迪宋PLUS");
        String fp2 = HybridRetriever.fingerprint("特斯拉Model Y");
        assertThat(fp1).isNotEqualTo(fp2);
    }

    @Test
    @DisplayName("长文本截取前200字符后指纹相同")
    void fingerprintTruncatesLongText() {
        String base = "A".repeat(250);
        String short_ = base.substring(0, 200); // 200个A
        String long_ = base + "额外尾部内容";    // 250个A + 尾部

        // 两者前200字符相同 → 指纹相同
        assertThat(HybridRetriever.fingerprint(short_))
                .isEqualTo(HybridRetriever.fingerprint(long_));
    }

    @Test
    @DisplayName("空/blank 文本 → 相同指纹 'empty'")
    void fingerprintEmptyText() {
        assertThat(HybridRetriever.fingerprint(null)).isEqualTo("empty");
        assertThat(HybridRetriever.fingerprint("")).isEqualTo("empty");
    }
}
