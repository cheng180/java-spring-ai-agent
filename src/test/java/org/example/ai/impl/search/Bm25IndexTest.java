package org.example.ai.impl.search;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bm25Index 单元测试 —— BM25 公式正确性、分词、排序。
 */
class Bm25IndexTest {

    private Bm25Index index;

    @BeforeEach
    void setUp() {
        index = new Bm25Index();
    }

    @AfterEach
    void tearDown() {
        index.clear();
    }

    // ---- 分词 ----

    @Test
    @DisplayName("中文 bigram + unigram 分词")
    void chineseTokenization() {
        List<String> tokens = Bm25Index.tokenize("省油的车");
        // bigrams: 省油, 油的, 的车
        assertThat(tokens).contains("省油", "油的", "的车");
        // unigrams: 省, 油, 的, 车
        assertThat(tokens).contains("省", "油", "的", "车");
    }

    @Test
    @DisplayName("中英混合分词")
    void mixedTokenization() {
        List<String> tokens = Bm25Index.tokenize("Model Y续航怎么样");
        // 英文转为小写
        assertThat(tokens).contains("model");
        assertThat(tokens).contains("y");
        // CJK bigrams + unigrams
        assertThat(tokens).contains("续航");
        assertThat(tokens).contains("怎么");
    }

    @Test
    @DisplayName("空输入分词 → 空列表")
    void emptyTokenization() {
        assertThat(Bm25Index.tokenize(null)).isEmpty();
        assertThat(Bm25Index.tokenize("")).isEmpty();
        assertThat(Bm25Index.tokenize("   ")).isEmpty();
    }

    @Test
    @DisplayName("纯数字/符号 → 保留原样")
    void numbersPreserved() {
        List<String> tokens = Bm25Index.tokenize("110km 旗舰型");
        assertThat(tokens).contains("110km");
        // bigram 分词："旗舰型" → "旗舰" + "舰型"
        assertThat(tokens).contains("旗舰", "舰型");
    }

    // ---- 索引构建 ----

    @Test
    @DisplayName("空索引 → search 返回空列表")
    void emptyIndexReturnsEmpty() {
        List<Bm25Index.ScoredDocument> results = index.search("比亚迪", 5);
        assertThat(results).isEmpty();
    }

    @Test
    @DisplayName("添加文档后 size 正确")
    void addDocumentIncreasesSize() {
        index.addDocument("d1", "比亚迪宋PLUS DM-i 110km旗舰型");
        assertThat(index.size()).isEqualTo(1);
        assertThat(index.isEmpty()).isFalse();
    }

    @Test
    @DisplayName("重复添加同 ID → 覆盖旧文档")
    void duplicateIdOverwrites() {
        index.addDocument("d1", "比亚迪宋PLUS");
        index.addDocument("d1", "比亚迪汉EV");
        assertThat(index.size()).isEqualTo(1);
        // 搜索"汉EV"应该命中
        List<Bm25Index.ScoredDocument> results = index.search("汉EV", 3);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).text()).contains("汉EV");
    }

    @Test
    @DisplayName("删除文档后 size 减少")
    void removeDocumentDecreasesSize() {
        index.addDocument("d1", "比亚迪宋PLUS");
        index.addDocument("d2", "特斯拉Model Y");
        assertThat(index.size()).isEqualTo(2);

        index.removeDocument("d1");
        assertThat(index.size()).isEqualTo(1);
        // 被删文档不再被搜到
        List<Bm25Index.ScoredDocument> results = index.search("比亚迪", 3);
        assertThat(results).isEmpty();
    }

    @Test
    @DisplayName("删除不存在的文档 → 无影响")
    void removeNonexistentNoOp() {
        index.addDocument("d1", "比亚迪宋PLUS");
        index.removeDocument("d999");
        assertThat(index.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("清空索引后 isEmpty=true")
    void clearEmptiesIndex() {
        index.addDocument("d1", "比亚迪宋PLUS");
        index.clear();
        assertThat(index.isEmpty()).isTrue();
        assertThat(index.size()).isEqualTo(0);
    }

    // ---- BM25 检索 ----

    @Test
    @DisplayName("精确匹配 → 高分排名靠前")
    void exactMatchScoresHighest() {
        index.addDocument("d1", "理想L6增程SUV");
        index.addDocument("d2", "比亚迪宋PLUS DM-i混动SUV");
        index.addDocument("d3", "比亚迪汉EV纯电轿车");

        List<Bm25Index.ScoredDocument> results = index.search("比亚迪", 3);
        assertThat(results).hasSizeGreaterThanOrEqualTo(2);
        // "比亚迪" 出现在 d2 和 d3 中，d1 不应出现
        assertThat(results.stream().map(Bm25Index.ScoredDocument::id))
                .contains("d2", "d3")
                .doesNotContain("d1");
    }

    @Test
    @DisplayName("多次出现的词 → 分数更高")
    void termFrequencyBoostsScore() {
        index.addDocument("d1", "比亚迪汉EV 比亚迪旗舰 比亚迪新能源");
        index.addDocument("d2", "特斯拉Model Y 偶尔提到比亚迪");

        List<Bm25Index.ScoredDocument> results = index.search("比亚迪", 3);
        assertThat(results).hasSizeGreaterThanOrEqualTo(2);
        // d1 出现3次"比亚迪"，d2 出现1次 → d1 应该排前面
        assertThat(results.get(0).id()).isEqualTo("d1");
        assertThat(results.get(0).score()).isGreaterThan(results.get(1).score());
    }

    @Test
    @DisplayName("topK 限制结果数量")
    void topKLimitsResults() {
        index.addDocument("d1", "比亚迪宋PLUS");
        index.addDocument("d2", "比亚迪汉EV");
        index.addDocument("d3", "比亚迪海鸥");
        index.addDocument("d4", "比亚迪秦PLUS");

        List<Bm25Index.ScoredDocument> results = index.search("比亚迪", 2);
        assertThat(results).hasSize(2);
    }

    @Test
    @DisplayName("无匹配查询 → 返回空列表")
    void noMatchReturnsEmpty() {
        index.addDocument("d1", "比亚迪宋PLUS");
        index.addDocument("d2", "特斯拉Model Y");

        List<Bm25Index.ScoredDocument> results = index.search("奔驰", 5);
        assertThat(results).isEmpty();
    }

    @Test
    @DisplayName("同一查询多次执行 → 分数一致（确定性）")
    void deterministicScoring() {
        index.addDocument("d1", "省油的混动SUV推荐");
        index.addDocument("d2", "纯电轿车续航长");

        List<Bm25Index.ScoredDocument> r1 = index.search("省油混动", 3);
        List<Bm25Index.ScoredDocument> r2 = index.search("省油混动", 3);

        assertThat(r1).hasSameSizeAs(r2);
        for (int i = 0; i < r1.size(); i++) {
            assertThat(r1.get(i).score()).isEqualTo(r2.get(i).score());
        }
    }

    @Test
    @DisplayName("null/blank 查询 → 返回空列表")
    void nullQueryReturnsEmpty() {
        index.addDocument("d1", "比亚迪宋PLUS");
        assertThat(index.search(null, 5)).isEmpty();
        assertThat(index.search("", 5)).isEmpty();
        assertThat(index.search("   ", 5)).isEmpty();
    }

    // ---- 中文检索场景 ----

    @Test
    @DisplayName("bigram 匹配：'混动' 找到含有该词的文档")
    void bigramMatch() {
        index.addDocument("d1", "比亚迪宋PLUS DM-i 混动SUV 省油");
        index.addDocument("d2", "特斯拉Model Y 纯电SUV 续航长");
        index.addDocument("d3", "丰田凯美瑞 混动轿车 油耗低");

        List<Bm25Index.ScoredDocument> results = index.search("混动", 5);
        assertThat(results).hasSize(2);
        assertThat(results.stream().map(Bm25Index.ScoredDocument::id))
                .containsExactlyInAnyOrder("d1", "d3");
    }

    @Test
    @DisplayName("多词组合查询 → 更相关文档分数更高")
    void multiTermQuery() {
        index.addDocument("d1", "比亚迪混动SUV 省油 家用 大空间");
        index.addDocument("d2", "理想混动SUV 家用 六座 增程");
        index.addDocument("d3", "特斯拉纯电轿车 性能版");

        // "混动 SUV 家用" 应该命中 d1 和 d2，d3 不应出现
        List<Bm25Index.ScoredDocument> results = index.search("混动SUV家用", 5);
        assertThat(results.stream().map(Bm25Index.ScoredDocument::id))
                .contains("d1", "d2")
                .doesNotContain("d3");
    }
}
