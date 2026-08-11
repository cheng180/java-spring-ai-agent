package org.example.ai.impl.context;

import org.example.ai.config.DynamicKeywordBuilder;
import org.example.ai.impl.routing.QueryLevelClassifier;
import org.example.ai.impl.search.HybridRetriever;
import org.example.ai.knowledge.entity.ResolvedEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * RetrievalContextAssembler 测试（#23 黄金锚点 + #24 车系级分层）。
 *
 * <p>UNRESTRICTED 用例锁定迁移前现状行为（字节级零回归守卫）；
 * SERIES 用例断言分层后的新行为（单系列父块 + 级别指令，零子块调用）。</p>
 */
@ExtendWith(MockitoExtension.class)
class RetrievalContextAssemblerTest {

    @Mock private HybridRetriever hybridRetriever;
    @Mock private VectorStore vectorStore;
    @Mock private org.example.ai.knowledge.hotness.AskCountTracker askCountTracker;

    private File tmpDb;
    private JdbcTemplate jdbc;
    private DynamicKeywordBuilder keywordBuilder;
    private RetrievalContextAssembler assembler;

    @BeforeEach
    void setUp() {
        // 分类器默认用空关键词表——UNRESTRICTED 锚点用例不经关键词路径，
        // SERIES 用例直接给实体；BRAND 用例自行播种后 rebuildAssembler()
        tmpDb = new File(System.getProperty("java.io.tmpdir"), "test-assembler-" + UUID.randomUUID() + ".db");
        var ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + tmpDb.getAbsolutePath());
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE car_sku (id INTEGER PRIMARY KEY, brand_name TEXT, series_name TEXT,"
                + " sale_status INTEGER DEFAULT 1, is_deleted INTEGER DEFAULT 0)");
        jdbc.execute("CREATE TABLE entity_mapping (entity_id TEXT NOT NULL, display_name TEXT NOT NULL,"
                + " aliases_json TEXT DEFAULT '[]', PRIMARY KEY (entity_id, display_name))");
        jdbc.execute("CREATE TABLE store_car_hot (id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + " store_id INTEGER NOT NULL, series_name TEXT NOT NULL,"
                + " sale_count INTEGER DEFAULT 0, inquiry_count INTEGER DEFAULT 0, stat_date DATE)");
        keywordBuilder = new DynamicKeywordBuilder(jdbc);
        rebuildAssembler();
    }

    private void rebuildAssembler() {
        keywordBuilder.rebuild();
        assembler = new RetrievalContextAssembler(hybridRetriever, vectorStore,
                new QueryLevelClassifier(keywordBuilder), askCountTracker, jdbc);
    }

    @AfterEach
    void tearDown() {
        if (tmpDb != null && tmpDb.exists()) tmpDb.delete();
    }

    // ---- 现状锚点 1：主路径（命中车系 → 父块 + 子块全量展开） ----

    @Test
    @DisplayName("主路径：父块召回命中 → 注入父块 + 子块全量展开，无百科/话术")
    void mainPathInjectsParentsAndAllChildren() {
        Document parent = doc("【比亚迪 宋PLUS DM-i】车系信息\n在售车型：2款",
                Map.of("series_id", "比亚迪-宋PLUS DM-i", "level", "parent"));
        when(hybridRetriever.search(anyString(), eq(3), eq(0.6), any(Filter.Expression.class)))
                .thenReturn(new java.util.ArrayList<>(List.of(parent)));

        Document child1 = doc("宋PLUS DM-i 旗舰，白色，全款15.88万",
                Map.of("sku_id", 1L, "level", "child"));
        Document child2 = doc("宋PLUS DM-i 尊贵，灰色，全款16.88万",
                Map.of("sku_id", 2L, "level", "child"));
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(child1, child2));

        String ctx = assembler.retrieveContext("有比亚迪宋吗", List.of()).context();

        // 字节级黄金输出：段标题、顺序、前缀格式全部锁定
        assertThat(ctx).isEqualTo(
                "## 匹配车系\n"
                + "【比亚迪 宋PLUS DM-i】车系信息\n"
                + "在售车型：2款\n"
                + "\n"
                + "## 在售车型\n"
                + "- 宋PLUS DM-i 旗舰，白色，全款15.88万\n"
                + "- 宋PLUS DM-i 尊贵，灰色，全款16.88万\n");
    }

    @Test
    @DisplayName("主路径：子块按 sku_id 去重")
    void mainPathDeduplicatesChildrenBySkuId() {
        Document parent = doc("父块文本", Map.of("series_id", "比亚迪-宋PLUS DM-i"));
        when(hybridRetriever.search(anyString(), eq(3), eq(0.6), any(Filter.Expression.class)))
                .thenReturn(new java.util.ArrayList<>(List.of(parent)));

        Document dup1 = doc("同一车源", Map.of("sku_id", 1L));
        Document dup2 = doc("同一车源（重复召回）", Map.of("sku_id", 1L));
        Document other = doc("另一车源", Map.of("sku_id", 2L));
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(dup1, dup2, other));

        String ctx = assembler.retrieveContext("宋PLUS", List.of()).context();

        assertThat(ctx).contains("同一车源").doesNotContain("重复召回");
        assertThat(ctx).contains("另一车源");
    }

    // ---- 现状锚点 2：回退路径（无命中 → 子块泛检索 + 百科/话术） ----

    @Test
    @DisplayName("回退路径：无任何车系命中 → 子块泛检索 + 百科话术，无父块段")
    void fallbackPathInjectsChildrenAndKnowledge() {
        when(hybridRetriever.search(anyString(), eq(3), eq(0.6), any(Filter.Expression.class)))
                .thenReturn(new java.util.ArrayList<>());

        Document child = doc("某车源子块", Map.of("level", "child"));
        Document kb = doc("购车话术段落", Map.of("type", "话术"));
        // 第一次调用=子块泛检索，第二次=百科/话术
        when(hybridRetriever.search(anyString(), eq(3), eq(0.5), any(Filter.Expression.class)))
                .thenReturn(List.of(child));
        when(hybridRetriever.search(anyString(), eq(2), eq(0.5), any(Filter.Expression.class)))
                .thenReturn(List.of(kb));

        String ctx = assembler.retrieveContext("买车要注意什么", List.of()).context();

        // 现状行为：无父块段时上下文以换行开头（字节级保留）
        assertThat(ctx).isEqualTo(
                "\n## 在售车型\n"
                + "- 某车源子块\n"
                + "\n"
                + "## 相关知识\n"
                + "- [话术] 购车话术段落\n");
        verify(vectorStore, never()).similaritySearch(any(SearchRequest.class));
    }

    // ---- #24：SERIES 级分层（单车系命中） ----

    @Test
    @DisplayName("SERIES 级：单车系命中 → 只注入完整父块+级别指令，零子块调用")
    void seriesLevelInjectsParentOnlyWithInstruction() {
        ResolvedEntity re = new ResolvedEntity(
                "entity:car:比亚迪:汉ev", "比亚迪-汉EV", "比亚迪", "汉EV");
        Document parent = doc("【比亚迪 汉EV】车系信息\n在售车型：1款",
                Map.of("series_id", "比亚迪-汉EV"));
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(parent));

        // "怎么样"不在细节词表 → 走车系级摘要
        String ctx = assembler.retrieveContext("汉EV怎么样", List.of(re)).context();

        assertThat(ctx).contains("## 匹配车系").contains("【比亚迪 汉EV】车系信息");
        assertThat(ctx).contains("级别指令");
        assertThat(ctx).doesNotContain("## 在售车型"); // 零子块
        verify(hybridRetriever, never())
                .search(anyString(), anyInt(), anyDouble(), any(Filter.Expression.class));
        verify(vectorStore, times(1)).similaritySearch(any(SearchRequest.class));
    }

    @Test
    @DisplayName("SERIES 级父块缺失（下架车系）→ 降级回退泛检索，绝不注入空上下文")
    void seriesLevelFallsBackWhenParentMissing() {
        ResolvedEntity re = new ResolvedEntity(
                "entity:car:某品牌:已下架车系", "某品牌-已下架车系", "某品牌", "已下架车系");
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        Document child = doc("回退子块", Map.of("level", "child"));
        when(hybridRetriever.search(anyString(), eq(3), eq(0.5), any(Filter.Expression.class)))
                .thenReturn(List.of(child));
        when(hybridRetriever.search(anyString(), eq(2), eq(0.5), any(Filter.Expression.class)))
                .thenReturn(List.of());

        String ctx = assembler.retrieveContext("已下架车系", List.of(re)).context();

        assertThat(ctx).contains("回退子块"); // 走了回退路径而非空上下文
    }

    @Test
    @DisplayName("价格门控：客户未问价 → SERIES 级上下文物理剥离价格（渐进式披露第一层）")
    void seriesLevelStripsPriceWhenNoInquiry() {
        ResolvedEntity re = new ResolvedEntity(
                "entity:car:宝马:宝马x3-m", "宝马-宝马X3 M", "宝马", "宝马X3 M");
        Document parent = doc("【宝马 宝马X3 M】车系信息\n价格区间：101.11万 ~ 101.11万\n"
                + "在售款型：\n  - 雷霆版 | 全款101.11万\n",
                Map.of("series_id", "宝马-宝马X3 M"));
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(parent));

        String ctx = assembler.retrieveContext("我想买宝马X3 M", List.of(re)).context();

        // 客户没问价：价格数字从上下文剥离，模型无价可抄
        assertThat(ctx).doesNotContain("价格区间");
        assertThat(ctx).doesNotContain("全款101.11万");
        assertThat(ctx).contains("雷霆版"); // 车型名保留（第一层仍可介绍定位/亮点）
        // 级别指令仍带披露约束（双保险）
        assertThat(ctx).contains("用户没明确问价就不要报");
    }

    @Test
    @DisplayName("价格门控：客户问价 → 上下文保留价格")
    void seriesLevelKeepsPriceWhenAskingPrice() {
        ResolvedEntity re = new ResolvedEntity(
                "entity:car:宝马:宝马x3-m", "宝马-宝马X3 M", "宝马", "宝马X3 M");
        Document parent = doc("【宝马 宝马X3 M】车系信息\n价格区间：101.11万 ~ 101.11万\n"
                + "在售款型：\n  - 雷霆版 | 全款101.11万\n",
                Map.of("series_id", "宝马-宝马X3 M"));
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(parent));

        String ctx = assembler.retrieveContext("宝马X3 M多少钱", List.of(re)).context();

        // 客户明确问价：价格保留在上下文（第三层披露）
        assertThat(ctx).contains("价格区间：101.11万 ~ 101.11万");
        assertThat(ctx).contains("全款101.11万");
    }

    // ---- #25：BRAND 级分层（品牌问句） ----

    @Test
    @DisplayName("BRAND 级：品牌问句 → 只注入数量+top1+指令，零向量库调用")
    void brandLevelInjectsMinimalContext() {
        seedSku(1, "比亚迪", "宋PLUS DM-i");
        seedSku(2, "比亚迪", "汉EV");
        seedSku(3, "比亚迪", "海鸥");
        rebuildAssembler();
        when(askCountTracker.getWeightedHeat("比亚迪-宋PLUS DM-i")).thenReturn(9.0);
        when(askCountTracker.getWeightedHeat("比亚迪-汉EV")).thenReturn(2.0);
        when(askCountTracker.getWeightedHeat("比亚迪-海鸥")).thenReturn(5.0);

        RetrievalContextAssembler.Result result = assembler.retrieveContext("有比亚迪吗", List.of());
        String ctx = result.context();

        // Result 契约：分类结果随上下文返回（对话日志级别归因依赖它）
        assertThat(result.classification().level())
                .isEqualTo(org.example.ai.impl.routing.QueryLevel.BRAND);
        assertThat(result.classification().brand()).isEqualTo("比亚迪");

        assertThat(ctx).contains("在售车系：3");
        assertThat(ctx).contains("宋PLUS DM-i");                          // 热度 top1
        assertThat(ctx).doesNotContain("汉EV").doesNotContain("海鸥");     // 不列其他车系
        assertThat(ctx).contains("级别指令");
        assertThat(ctx.length()).as("上下文长度上限").isLessThan(800);
        verify(vectorStore, never()).similaritySearch(any(SearchRequest.class));
        verify(hybridRetriever, never())
                .search(anyString(), anyInt(), anyDouble(), any(Filter.Expression.class));
    }

    @Test
    @DisplayName("BRAND 级：热度并列/全零 → 门店销量全局求和兜底排序")
    void brandLevelFallsBackToSalesWhenHeatTied() {
        seedSku(1, "比亚迪", "宋PLUS DM-i");
        seedSku(2, "比亚迪", "汉EV");
        rebuildAssembler();
        // 热度全零（mock 默认值）；销量：汉EV 30 > 宋PLUS DM-i 10
        jdbc.update("INSERT INTO store_car_hot (store_id, series_name, sale_count, stat_date)"
                + " VALUES (1,'汉EV',30,date('now'))");
        jdbc.update("INSERT INTO store_car_hot (store_id, series_name, sale_count, stat_date)"
                + " VALUES (1,'宋PLUS DM-i',10,date('now'))");

        String ctx = assembler.retrieveContext("比亚迪", List.of()).context();

        assertThat(ctx).contains("汉EV");      // 销量兜底 top1
        assertThat(ctx).doesNotContain("宋PLUS"); // 其他车系不进上下文
    }

    // ---- #26：FAMILY 级分层（同品牌多车系） ----

    @Test
    @DisplayName("FAMILY 级：同品牌多车系 → 只取命中车系父块 + 截断在售款型段")
    void familyLevelInjectsTruncatedParentsOnly() {
        // 种子 4 个比亚迪车系（2 实体 < 4 总数 → FAMILY 而非 BRAND）
        seedSku(1, "比亚迪", "秦L");
        seedSku(2, "比亚迪", "秦PLUS DM-i");
        seedSku(3, "比亚迪", "汉EV");
        seedSku(4, "比亚迪", "海鸥");
        rebuildAssembler();

        Document qinL = doc("【比亚迪 秦L】车系信息\n价格区间：10.00万 ~ 14.00万\n"
                + "在售款型：\n  - 秦L 2026款 | 全款10.98万\n近期热度：5.0\n",
                Map.of("series_id", "比亚迪-秦L"));
        Document qinPlus = doc("【比亚迪 秦PLUS DM-i】车系信息\n价格区间：8.00万 ~ 12.00万\n"
                + "在售款型：\n  - 秦PLUS 荣耀版 | 全款8.98万\n",
                Map.of("series_id", "比亚迪-秦PLUS DM-i"));
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(qinL))
                .thenReturn(List.of(qinPlus));

        String ctx = assembler.retrieveContext("比亚迪秦",
                List.of(entity("比亚迪", "秦L"), entity("比亚迪", "秦PLUS DM-i"))).context();

        assertThat(ctx).doesNotContain("价格区间");             // 未问价 → 价格剥离
        assertThat(ctx).doesNotContain("秦L 2026款");              // 车型清单被截断
        assertThat(ctx).doesNotContain("秦PLUS 荣耀版");
        assertThat(ctx).doesNotContain("在售款型：");
        assertThat(ctx).contains("级别指令");
        assertThat(ctx).contains("用户没明确问价就不要报");         // #32 FAMILY 级披露约束
        verify(vectorStore, times(2)).similaritySearch(any(SearchRequest.class));
    }

    @Test
    @DisplayName("FAMILY 级：截断锚点丢失 → fail-safe 保留全文（宁长勿错）")
    void familyTruncationFailSafeKeepsFullText() {
        seedSku(1, "比亚迪", "秦L");
        seedSku(2, "比亚迪", "秦PLUS DM-i");
        seedSku(3, "比亚迪", "汉EV");
        rebuildAssembler();

        // 模拟父块格式变化：有车型内容但没有"在售款型："锚点
        Document noAnchor = doc("【比亚迪 秦L】车系信息\n价格区间：10.00万 ~ 14.00万\n"
                + "车型列表：\n  - 秦L 2026款 | 全款10.98万\n",
                Map.of("series_id", "比亚迪-秦L"));
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(noAnchor))
                .thenReturn(List.of());

        String ctx = assembler.retrieveContext("比亚迪秦",
                List.of(entity("比亚迪", "秦L"), entity("比亚迪", "秦PLUS DM-i"))).context();

        assertThat(ctx).contains("秦L 2026款"); // 锚点未命中 → 保留全文，不做半截截断
    }

    @Test
    @DisplayName("FAMILY 级：父块全部缺失 → 降级回退泛检索")
    void familyFallsBackWhenAllParentsMissing() {
        seedSku(1, "比亚迪", "秦L");
        seedSku(2, "比亚迪", "秦PLUS DM-i");
        seedSku(3, "比亚迪", "汉EV");
        rebuildAssembler();
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        Document child = doc("回退子块", Map.of("level", "child"));
        when(hybridRetriever.search(anyString(), eq(3), eq(0.5), any(Filter.Expression.class)))
                .thenReturn(List.of(child));
        when(hybridRetriever.search(anyString(), eq(2), eq(0.5), any(Filter.Expression.class)))
                .thenReturn(List.of());

        String ctx = assembler.retrieveContext("比亚迪秦",
                List.of(entity("比亚迪", "秦L"), entity("比亚迪", "秦PLUS DM-i"))).context();

        assertThat(ctx).contains("回退子块"); // 走了回退路径而非空上下文
    }

    @Test
    @DisplayName("BRAND 级：品牌无在售车系数据 → 降级回退泛检索（防越界）")
    void brandLevelFallsBackWhenNoSeriesData() {
        // 空关键词表 + 4 个同品牌实体 → 分类器判 BRAND（size>3），但 seriesKeys 为空
        // （模拟 entity_mapping 与关键词表刷新窗口期的瞬时不一致）
        List<org.example.ai.knowledge.entity.ResolvedEntity> four = List.of(
                entity("比亚迪", "车系A"), entity("比亚迪", "车系B"),
                entity("比亚迪", "车系C"), entity("比亚迪", "车系D"));

        Document child = doc("回退子块", Map.of("level", "child"));
        when(hybridRetriever.search(anyString(), eq(3), eq(0.5), any(Filter.Expression.class)))
                .thenReturn(List.of(child));
        when(hybridRetriever.search(anyString(), eq(2), eq(0.5), any(Filter.Expression.class)))
                .thenReturn(List.of());

        String ctx = assembler.retrieveContext("比亚迪", four).context();

        assertThat(ctx).contains("回退子块"); // 降级回退而非越界异常
    }

    // ---- helpers ----

    private void seedSku(long id, String brand, String series) {
        jdbc.update("INSERT INTO car_sku (id, brand_name, series_name) VALUES (?,?,?)",
                id, brand, series);
    }

    private static org.example.ai.knowledge.entity.ResolvedEntity entity(String brand, String series) {
        return new org.example.ai.knowledge.entity.ResolvedEntity(
                "entity:" + brand + ":" + series, brand + "-" + series, brand, series);
    }

    private static Document doc(String text, Map<String, Object> meta) {
        return new Document(text, new java.util.HashMap<>(meta));
    }
}