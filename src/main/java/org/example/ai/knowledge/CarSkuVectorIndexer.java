package org.example.ai.knowledge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 车源 SKU 向量化 —— 把 car_sku 表的上架车源写入向量库
 *
 * 切分策略：【不使用任何 TextSplitter】
 * 每条 SKU 天然就是一个完整的语义单元（一款车+颜色+价格+车商），
 * 由程序渲染成一段自然语言文本，一条 = 一个 Document。
 * 用切分器反而会把一条记录从中间切断，还会混入 SQL 语法噪声。
 *
 * 同步策略：每次启动按 metadata type='车源' 删除旧向量再全量写入。
 * 车源是会变动的业务数据（上下架、改价），这样保证向量库与数据库一致；
 * 47 条规模全量刷新成本可以忽略（ embedding 只调 1-2 批）。
 *
 * @Order(3)：必须在 DatabaseInitializer(1)、KnowledgeBaseInitializer(2) 之后
 */
@Component
@Order(3)
public class CarSkuVectorIndexer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(CarSkuVectorIndexer.class);

    /** 向量库中车源文档的类型标识（与 百科/话术 区分，便于过滤和刷新） */
    public static final String DOC_TYPE = "车源";

    private final VectorStore vectorStore;
    private final JdbcTemplate jdbc;

    public CarSkuVectorIndexer(VectorStore vectorStore, JdbcTemplate jdbc) {
        this.vectorStore = vectorStore;
        this.jdbc = jdbc;
    }

    @Override
    public void run(String... args) {
        log.info("=== 开始同步车源向量（一条 SKU = 一个 Document，无切分器） ===");

        // 1. 删除旧的车源向量（按 metadata 过滤，不影响百科/话术）
        try {
            vectorStore.delete(new FilterExpressionBuilder()
                    .eq("type", DOC_TYPE).build());
            log.info("已清除旧车源向量");
        } catch (Exception e) {
            log.warn("清除旧车源向量失败（可能尚不存在），继续写入: {}", e.getMessage());
        }

        // 2. 读取上架且未删除的车源
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM car_sku WHERE sale_status = 1 AND is_deleted = 0 ORDER BY id");
        if (rows.isEmpty()) {
            log.warn("car_sku 表没有上架车源，跳过向量化");
            return;
        }

        // 3. 每条 SKU 渲染成一段自然语言文本 → 一个 Document
        List<Document> docs = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("type", DOC_TYPE);
            metadata.put("source", "car_sku表");
            metadata.put("sku_id", ((Number) r.get("id")).longValue());
            metadata.put("brand_name", str(r.get("brand_name")));
            metadata.put("series_name", str(r.get("series_name")));
            metadata.put("energy_type", energyText(r.get("energy_type")));
            if (r.get("sale_price") != null) {
                metadata.put("sale_price_wan", ((Number) r.get("sale_price")).doubleValue() / 1_000_000.0);
            }
            docs.add(new Document(render(r), metadata));
        }

        // 4. embedding + 写入 Chroma
        vectorStore.add(docs);
        log.info("=== 车源向量同步完成，共 {} 条 ===", docs.size());
        log.debug("示例渲染: {}", docs.get(0).getText());
    }

    /**
     * 把一条 SKU 渲染成自然语言描述 —— 这是 embedding 质量的成败点。
     * 原则：客户会怎么问，文本就怎么写（品牌/车系/车型/颜色/价格/能源/保障信息）
     */
    private String render(Map<String, Object> r) {
        StringBuilder sb = new StringBuilder();
        sb.append(str(r.get("model_name")));                      // 车型全称（已含品牌车系年款配置）
        sb.append("，").append(str(r.get("outer_color_name")));   // 颜色
        sb.append("，").append(energyText(r.get("energy_type"))); // 能源
        sb.append("，指导价").append(str(r.get("guide_price")));
        if (r.get("sale_price") != null) {
            sb.append(String.format("，全款销售价%.2f万", ((Number) r.get("sale_price")).doubleValue() / 1_000_000.0));
        }
        if (r.get("sale_price_finance") != null) {
            sb.append(String.format("，金融方案价%.2f万", ((Number) r.get("sale_price_finance")).doubleValue() / 1_000_000.0));
        }
        sb.append("，").append(str(r.get("spec_name")));          // 车规（中规/国产等）
        if (isTrue(r.get("in_store_insurance"))) sb.append("，店内保险");
        if (isTrue(r.get("can_issue_vat_invoice"))) sb.append("，可开增票");
        sb.append("，车商：").append(str(r.get("owner_name")));
        String memo = str(r.get("memo"));
        if (!memo.isBlank()) {
            sb.append("。备注：").append(memo);
        }
        return sb.toString();
    }

    private String str(Object o) {
        return o == null ? "" : o.toString();
    }

    private boolean isTrue(Object o) {
        return o != null && ((Number) o).intValue() == 1;
    }

    private String energyText(Object type) {
        if (type == null) return "未知能源";
        return ((Number) type).intValue() == 2 ? "新能源" : "燃油车";
    }
}