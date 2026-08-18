package org.example.ai.impl.search;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * BM25 索引构建器 —— 启动时从数据源加载所有文档构建内存 BM25 索引。
 *
 * 数据源：
 * 1. car_sku 表（车源子块）：brand_name + series_name + model_name + outer_color + energy + price
 * 2. car_sku 聚合父块（车系级）：series_name + 车型数 + 价格区间 + 能源/颜色覆盖
 * 3. knowledge/*.md + knowledge/*.txt（百科/话术）：按段落行拆分
 *
 * 索引内容与 Chroma 向量库保持一致，用于 BM25 + BGE-M3 混合检索。
 */
@Component
@Order(4)  // 必须晚于 DatabaseInitializer(@Order 1)：它先建 car_sku 表并灌种子，本类才能读
public class Bm25Indexer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(Bm25Indexer.class);

    private final JdbcTemplate jdbc;
    private final ResourcePatternResolver resourceResolver;

    private final Bm25Index index = new Bm25Index();
    private volatile boolean ready = false;

    public Bm25Indexer(JdbcTemplate jdbc, ResourcePatternResolver resourceResolver) {
        this.jdbc = jdbc;
        this.resourceResolver = resourceResolver;
    }

    /**
     * 启动时自动构建索引（CommandLineRunner，@Order(4)，晚于 DatabaseInitializer 建表灌种子）。
     * 车源变更广播处理后由更新链路（InMemoryIndexRefresher）再次调用 rebuild() 刷新。
     */
    @Override
    public void run(String... args) {
        rebuild();
    }

    /**
     * 全量重建 BM25 索引。
     */
    public synchronized void rebuild() {
        index.clear();
        ready = false;

        int count = 0;
        count += indexCarSkuChildDocs();
        count += indexCarSkuParentDocs();
        count += indexKnowledgeFiles();

        ready = true;
        log.info("Bm25Indexer: 索引构建完成，共 {} 篇文档", count);
    }

    /** 获取 BM25 索引（用于检索）。 */
    public Bm25Index getIndex() {
        return index;
    }

    /** 索引是否就绪。 */
    public boolean isReady() {
        return ready;
    }

    // ---- 数据源 1：car_sku 子块 ----

    private int indexCarSkuChildDocs() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, brand_name, series_name, model_name, outer_color_name," +
                " guide_price, sale_price, sale_price_finance, energy_type, memo" +
                " FROM car_sku WHERE sale_status = 1 AND is_deleted = 0 ORDER BY id");

        for (Map<String, Object> r : rows) {
            String docId = "sku-" + r.get("id");
            String text = buildSkuText(r);
            index.addDocument(docId, text);
        }
        log.debug("Bm25Indexer: 索引 {} 条车源子块", rows.size());
        return rows.size();
    }

    private String buildSkuText(Map<String, Object> r) {
        StringBuilder sb = new StringBuilder();
        sb.append(str(r.get("brand_name"))).append(" ");
        sb.append(str(r.get("series_name"))).append(" ");
        sb.append(str(r.get("model_name"))).append(" ");
        sb.append("颜色").append(str(r.get("outer_color_name"))).append(" ");
        sb.append("指导价").append(str(r.get("guide_price"))).append(" ");
        sb.append("售价").append(fenToWan(r.get("sale_price"))).append(" ");
        int energy = r.get("energy_type") instanceof Number ? ((Number) r.get("energy_type")).intValue() : 0;
        sb.append(energy == 2 ? "新能源" : "燃油车").append(" ");
        String memo = str(r.get("memo"));
        if (!memo.isEmpty()) sb.append("备注").append(memo);
        return sb.toString();
    }

    // ---- 数据源 2：car_sku 父块（车系聚合） ----

    private int indexCarSkuParentDocs() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT series_name, COUNT(*) AS model_count," +
                " MIN(CAST(sale_price AS REAL)) AS min_price," +
                " MAX(CAST(sale_price AS REAL)) AS max_price," +
                " GROUP_CONCAT(DISTINCT outer_color_name) AS colors," +
                " GROUP_CONCAT(DISTINCT CASE WHEN energy_type=2 THEN '新能源' ELSE '燃油车' END) AS energy_types" +
                " FROM car_sku WHERE sale_status = 1 AND is_deleted = 0" +
                " GROUP BY series_name ORDER BY series_name");

        for (Map<String, Object> r : rows) {
            String series = str(r.get("series_name"));
            String docId = "parent-" + series;
            StringBuilder sb = new StringBuilder();
            sb.append(series).append(" 在售车型 ");
            Number mc = (Number) r.get("model_count");
            sb.append(mc != null ? mc.intValue() : 0).append("款 ");
            sb.append("颜色 ").append(str(r.get("colors"))).append(" ");
            sb.append("能源 ").append(str(r.get("energy_types"))).append(" ");
            sb.append("价格区间 ");
            Number minP = (Number) r.get("min_price");
            Number maxP = (Number) r.get("max_price");
            sb.append(minP != null ? fenToWanRaw(minP.doubleValue()) : "?");
            sb.append("~");
            sb.append(maxP != null ? fenToWanRaw(maxP.doubleValue()) : "?");
            index.addDocument(docId, sb.toString());
        }
        log.debug("Bm25Indexer: 索引 {} 条车系父块", rows.size());
        return rows.size();
    }

    // ---- 数据源 3：百科/话术文件 ----

    private int indexKnowledgeFiles() {
        int count = 0;
        try {
            Resource[] all = allKnowledgeFiles();
            for (Resource res : all) {
                String filename = res.getFilename();
                String content = res.getContentAsString(StandardCharsets.UTF_8);
                // 按段落拆分（空行分隔）
                String[] paragraphs = content.split("\n\n+");
                for (int i = 0; i < paragraphs.length; i++) {
                    String para = paragraphs[i].trim();
                    if (para.length() < 10) continue; // 跳过过短段落
                    // 去除 markdown 标题标记
                    para = para.replaceAll("^#{1,6}\\s+", "");
                    if (para.length() < 10) continue;
                    String docId = "kb-" + filename + "-p" + i;
                    index.addDocument(docId, para);
                    count++;
                }
            }
        } catch (IOException e) {
            log.warn("Bm25Indexer: 加载知识库文件失败: {}", e.getMessage());
        }
        log.debug("Bm25Indexer: 索引 {} 条百科/话术段落", count);
        return count;
    }

    private Resource[] allKnowledgeFiles() throws IOException {
        Resource[] md = resourceResolver.getResources("classpath:knowledge/*.md");
        Resource[] txt = resourceResolver.getResources("classpath:knowledge/*.txt");
        Resource[] all = new Resource[md.length + txt.length];
        System.arraycopy(md, 0, all, 0, md.length);
        System.arraycopy(txt, 0, all, md.length, txt.length);
        return all;
    }

    // ---- 工具方法 ----

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }

    private String fenToWan(Object fen) {
        if (fen == null) return "价格待询";
        double wan = ((Number) fen).doubleValue() / 1_000_000.0;
        return String.format("%.2f万", wan);
    }

    private String fenToWanRaw(double fen) {
        double wan = fen / 1_000_000.0;
        return String.format("%.2f万", wan);
    }
}
