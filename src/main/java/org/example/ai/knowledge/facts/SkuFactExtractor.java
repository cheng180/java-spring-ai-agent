package org.example.ai.knowledge.facts;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 车源原子事实提取器（SQL+模板渲染路径，不用LLM —— #1 决策2路径A）。
 *
 * 分块策略：一条 car_sku 行 = 一个子块原子事实。
 * 不使用 TokenTextSplitter —— 每条 SKU 天然就是完整的语义单元。
 *
 * 事实带：temporal_type=DYNAMIC（价格字段可变）、entity_id、source_hash、完整 metadata。
 */
@Component
public class SkuFactExtractor {

    private static final Logger log = LoggerFactory.getLogger(SkuFactExtractor.class);

    /* 能源类型映射（对齐公司库：1=燃油，2=新能源） */
    private static final Map<Integer, String> ENERGY_MAP = Map.of(1, "燃油车", 2, "新能源");

    private final JdbcTemplate jdbc;

    public SkuFactExtractor(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 从 car_sku 表提取所有上架且未删除的车源为子块原子事实。
     */
    public List<AtomicFact> extractAll() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM car_sku WHERE sale_status = 1 AND is_deleted = 0 ORDER BY id");

        List<AtomicFact> facts = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            facts.add(toChildFact(row));
        }
        log.info("SkuFactExtractor: 提取 {} 条子块原子事实", facts.size());
        return facts;
    }

    /**
     * 单条 SKU → 子块 AtomicFact。
     * 保留现有 render() 逻辑不变（#2 决策7），增加实体ID + 时序标记 + 内容哈希。
     */
    public AtomicFact toChildFact(Map<String, Object> row) {
        long skuId = ((Number) row.get("id")).longValue();
        String brand = str(row.get("brand_name"));
        String series = str(row.get("series_name"));
        String content = render(row);
        String entityId = buildEntityId(brand, series);
        String hash = sha256(content);

        Map<String, Object> meta = new HashMap<>();
        meta.put("type", "车源");
        meta.put("level", "child");
        meta.put("parent_series_id", brand + "-" + series);
        meta.put("sku_id", skuId);
        meta.put("brand_name", brand);
        meta.put("series_name", series);
        meta.put("energy_type", energyText(row.get("energy_type")));
        if (row.get("sale_price") != null) {
            meta.put("sale_price_wan", ((Number) row.get("sale_price")).doubleValue() / 1_000_000.0);
        }

        return AtomicFact.builder()
                .factId("sku-" + skuId)
                .content(content)
                .entityId(entityId)
                .temporalType(TemporalType.DYNAMIC) // 价格字段可变
                .sourceDoc("car_sku表")
                .sourceHash(hash)
                .metadataAll(meta)
                .build();
    }

    /**
     * 品牌+车系 → 归一化实体ID（#1 决策3）。
     * 如 "比亚迪" + "宋PLUS DM-i" → "entity:car:byd:song-plus-dm-i"
     */
    public static String buildEntityId(String brand, String series) {
        return "entity:car:" + toSlug(brand) + ":" + toSlug(series);
    }

    /**
     * 自然语言渲染 —— 与现有 CarSkuVectorIndexer.render() 一致（#2 决策7）。
     * 客户会怎么问，文本就怎么写。
     *
     * <p>回复过长治理（2026-08-11）：文本改为「客户视角人话」——
     * 通俗车系名（品牌+车系）开头作为展示锚点，完整款型名降级为「内部款型」
     * 标识（模型可据此回答款型细节，但不得报给客户）；价格只保留指导价一个，
     * 用「价格xx万」表述，不再出现指导价/全款/金融方案多个价格字段。</p>
     */
    private String render(Map<String, Object> r) {
        String brand = str(r.get("brand_name"));
        String series = str(r.get("series_name"));
        StringBuilder sb = new StringBuilder();
        sb.append("【").append(brand).append(" ").append(series).append("】");
        sb.append("，内部款型：").append(str(r.get("model_name")));
        sb.append("，颜色：").append(str(r.get("outer_color_name")));
        sb.append("，").append(energyText(r.get("energy_type")));
        String price = str(r.get("guide_price"));
        sb.append("，价格").append(price.isBlank() ? "待询" : price);
        String spec = str(r.get("spec_name"));
        if (!spec.isBlank()) sb.append("，规格：").append(spec);
        if (isTrue(r.get("in_store_insurance"))) sb.append("，店内保险");
        if (isTrue(r.get("can_issue_vat_invoice"))) sb.append("，可开增票");
        sb.append("，车商：").append(str(r.get("owner_name")));
        String memo = str(r.get("memo"));
        if (!memo.isBlank()) sb.append("。备注：").append(memo);
        return sb.toString();
    }

    /** 计算 SHA-256 哈希（#2 决策10），跨包可调用 */
    public static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    static String toSlug(String s) {
        if (s == null || s.isBlank()) return "unknown";
        return s.trim().toLowerCase()
                .replaceAll("[^a-z0-9\\u4e00-\\u9fff]+", "-")
                .replaceAll("^-|-$", "");
    }

    private String str(Object o) { return o == null ? "" : o.toString(); }
    private boolean isTrue(Object o) { return o != null && ((Number) o).intValue() == 1; }

    private String energyText(Object type) {
        if (type == null) return "未知能源";
        return ENERGY_MAP.getOrDefault(((Number) type).intValue(), "未知能源");
    }
}