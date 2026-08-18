package org.example.ai.knowledge.update;

import org.example.ai.config.DatabaseInitializer;
import org.example.ai.config.DynamicKeywordBuilder;
import org.example.ai.impl.search.Bm25Index;
import org.example.ai.impl.search.Bm25Indexer;
import org.example.ai.knowledge.entity.EntityResolver;
import org.example.ai.knowledge.entity.ResolvedEntity;
import org.example.ai.knowledge.facts.SeriesParentBuilder;
import org.example.ai.knowledge.facts.SkuFactExtractor;
import org.example.ai.knowledge.hotness.AskCountTracker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * 知识库动态更新端到端测试 —— 复现"必须重启服务器才能更新知识库"。
 *
 * <p>模拟完整链路：</p>
 * <ol>
 *   <li>服务器已启动：各内存索引按启动流程构建完毕（setUp 模拟）</li>
 *   <li>公司车源数据库（此处为模拟库 car_sku）某条目发生变更</li>
 *   <li>变更广播到达（Mock MQ = {@link SkuChangeListener#onSkuChanged}）</li>
 *   <li>断言：<b>不重启</b>的情况下，问答侧所有知识结构都反映新数据</li>
 * </ol>
 *
 * <p>问答侧实际消费的知识结构有四份（见 CarSalesAgent/HybridRetriever）：
 * Chroma 向量、BM25 内存索引（Bm25Indexer）、实体别名索引（EntityResolver）、
 * 动态关键词表（DynamicKeywordBuilder）。任何一份未随广播更新，
 * 用户都会感知为"知识库没更新，得重启"。</p>
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeDynamicUpdateTest {

    @Mock private VectorStore vectorStore;
    @Mock private AskCountTracker askCountTracker;

    private File tmpDb;
    private JdbcTemplate jdbc;

    private SkuVectorUpdater updater;
    private Bm25Indexer bm25Indexer;
    private EntityResolver entityResolver;
    private DynamicKeywordBuilder keywordBuilder;

    @BeforeEach
    void setUp() {
        // ---- 模拟公司车源数据库（SQLite 临时文件） ----
        tmpDb = new File(System.getProperty("java.io.tmpdir"),
                "test-dynupdate-" + UUID.randomUUID() + ".db");
        var ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + tmpDb.getAbsolutePath());
        jdbc = new JdbcTemplate(ds);

        jdbc.execute("""
            CREATE TABLE car_sku (
                id INTEGER PRIMARY KEY, owner_name TEXT, spec_name TEXT,
                brand_name TEXT, series_name TEXT, model_name TEXT,
                outer_color_name TEXT, guide_price TEXT, bare_car_price INTEGER,
                settlement_price INTEGER, sale_price INTEGER, sale_price_finance INTEGER,
                intended_landing_price INTEGER, sku_tag TEXT, energy_type INTEGER DEFAULT 1,
                is_virtual_source INTEGER DEFAULT 0, in_store_insurance INTEGER DEFAULT 1,
                can_issue_vat_invoice INTEGER DEFAULT 1, sale_status INTEGER DEFAULT 1,
                memo TEXT, sku_picture TEXT, is_deleted INTEGER DEFAULT 0
            )""");
        jdbc.execute("""
            CREATE TABLE entity_mapping (
                entity_id TEXT NOT NULL, display_name TEXT NOT NULL,
                aliases_json TEXT DEFAULT '[]', PRIMARY KEY (entity_id, display_name)
            )""");
        jdbc.execute("""
            CREATE TABLE vector_sync_log (
                sku_id INTEGER PRIMARY KEY, source_hash TEXT NOT NULL,
                last_synced_at TEXT DEFAULT (datetime('now','localtime'))
            )""");

        // 种子：一条在售车源（等价于启动时 DatabaseInitializer 的种子数据）
        insertSku(1L, "比亚迪", "宋PLUS DM-i", "宋PLUS DM-i 旗舰型", "白色",
                "15.98万", 14580000L, 2);
        // 种子：启动时构建的实体映射（等价于 DatabaseInitializer.seedEntityMapping）
        jdbc.update("INSERT INTO entity_mapping (entity_id, display_name, aliases_json) VALUES (?, ?, ?)",
                "entity:car:比亚迪:宋plus-dm-i", "比亚迪-宋PLUS DM-i", "[\"宋plus\",\"BYD-宋PLUS DM-i\"]");

        // ---- 模拟"服务器启动"：构建更新器 + 各内存索引 ----
        var skuExtractor = new SkuFactExtractor(jdbc);
        var parentBuilder = new SeriesParentBuilder(jdbc, skuExtractor, askCountTracker);

        bm25Indexer = new Bm25Indexer(jdbc, new PathMatchingResourcePatternResolver());
        bm25Indexer.rebuild();                          // 启动时构建 BM25 索引
        entityResolver = new EntityResolver(jdbc);      // 启动时构建别名索引
        entityResolver.run();            // 模拟 CommandLineRunner 启动回调
        keywordBuilder = new DynamicKeywordBuilder(jdbc);
        keywordBuilder.run();            // 启动时构建关键词表

        var refresher = new InMemoryIndexRefresher(
                new DatabaseInitializer(jdbc), entityResolver, keywordBuilder, bm25Indexer);
        updater = new SkuVectorUpdater(vectorStore, skuExtractor, parentBuilder, jdbc, refresher,
                io.micrometer.observation.ObservationRegistry.NOOP);
    }

    @AfterEach
    void tearDown() {
        if (tmpDb != null && tmpDb.exists()) tmpDb.delete();
    }

    // ==================== 场景一：改价 ====================

    @Test
    @DisplayName("UPDATE 广播后不重启：Chroma 子块反映新价格")
    void updateBroadcastRefreshesVectorStore() {
        jdbc.update("UPDATE car_sku SET sale_price = 9990000 WHERE id = 1"); // 公司库改价：14.58万→9.99万
        updater.onSkuChanged(new SkuChangeEvent(1L, ChangeType.UPDATE, Map.of())); // 广播到达

        List<Document> added = captureAddedDocs();
        assertThat(added)
                .as("广播后 Chroma 应写入含新价格的子块")
                .anySatisfy(doc -> assertThat(doc.getText()).contains("9.99万"));
    }

    @Test
    @DisplayName("UPDATE 广播后不重启：BM25 内存索引反映新价格")
    void updateBroadcastRefreshesBm25Index() {
        jdbc.update("UPDATE car_sku SET sale_price = 9990000 WHERE id = 1");
        updater.onSkuChanged(new SkuChangeEvent(1L, ChangeType.UPDATE, Map.of()));

        // 问答检索走 HybridRetriever = BM25 + 向量融合，BM25 索引必须同步
        Bm25Index index = bm25Indexer.getIndex();
        List<Bm25Index.ScoredDocument> hits = index.search("宋PLUS DM-i 售价", 10);
        assertThat(hits)
                .as("广播后 BM25 索引中 sku-1 文档应为新价格")
                .filteredOn(d -> "sku-1".equals(d.id()))
                .anySatisfy(d -> assertThat(d.text()).contains("9.99万"));
    }

    // ==================== 场景二：新增车系 ====================

    @Test
    @DisplayName("INSERT 新车系广播后不重启：Chroma 写入新车系")
    void insertBroadcastWritesNewSeriesVector() {
        insertSku(2L, "吉利", "银河E5", "银河E5 530km 远航版", "灰色",
                "12.98万", 11980000L, 2);
        updater.onSkuChanged(new SkuChangeEvent(2L, ChangeType.INSERT, Map.of()));

        List<Document> added = captureAddedDocs();
        assertThat(added)
                .as("广播后 Chroma 应写入新车系子块")
                .anySatisfy(doc -> assertThat(doc.getText()).contains("银河E5"));
    }

    @Test
    @DisplayName("INSERT 新车系广播后不重启：实体解析能识别新车系")
    void insertBroadcastRefreshesEntityResolver() {
        insertSku(2L, "吉利", "银河E5", "银河E5 530km 远航版", "灰色",
                "12.98万", 11980000L, 2);
        updater.onSkuChanged(new SkuChangeEvent(2L, ChangeType.INSERT, Map.of()));

        // 客户问"吉利银河E5多少钱"，EntityResolver 必须能识别，
        // 否则车系确定性展开/路由全部失效
        List<ResolvedEntity> resolved = entityResolver.resolve("吉利银河E5多少钱");
        assertThat(resolved)
                .as("广播后 EntityResolver 应能识别新车系（不重启）")
                .isNotEmpty();
    }

    @Test
    @DisplayName("INSERT 新车系广播后不重启：关键词表覆盖新车系")
    void insertBroadcastRefreshesKeywords() {
        insertSku(2L, "吉利", "银河E5", "银河E5 530km 远航版", "灰色",
                "12.98万", 11980000L, 2);
        updater.onSkuChanged(new SkuChangeEvent(2L, ChangeType.INSERT, Map.of()));

        // DynamicKeywordBuilder 驱动闲聊判定与模糊查询路由，
        // 不认识新车系 → 用户提问被当成闲聊或路由失败
        assertThat(keywordBuilder.containsAnyKeyword("银河E5这车怎么样"))
                .as("广播后关键词表应覆盖新车系（不重启）")
                .isTrue();
    }

    // ==================== 辅助 ====================

    /** 捕获广播后写入向量库的全部文档 */
    @SuppressWarnings("unchecked")
    private List<Document> captureAddedDocs() {
        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore, atLeastOnce()).add(captor.capture());
        List<Document> all = new ArrayList<>();
        captor.getAllValues().forEach(all::addAll);
        return all;
    }

    private void insertSku(long id, String brand, String series, String model,
                           String color, String guidePrice, long salePrice, int energyType) {
        jdbc.update("""
            INSERT INTO car_sku (id, owner_name, spec_name, brand_name, series_name, model_name,
                outer_color_name, guide_price, sale_price, energy_type, sale_status, is_deleted)
            VALUES (?, '测试车商', '标准版', ?, ?, ?, ?, ?, ?, ?, 1, 0)
        """, id, brand, series, model, color, guidePrice, salePrice, energyType);
    }
}