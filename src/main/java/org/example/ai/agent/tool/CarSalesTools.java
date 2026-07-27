package org.example.ai.agent.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 汽车销售工具集 —— 暴露给 LLM 调用的工具箱
 * 数据源：car_sku 表（对齐公司真实车源表 aito_car_sku，金额单位：分）
 * LLM 自主决定何时调用哪个工具，代码不做流程编排
 */
@Component
public class CarSalesTools {

    private static final Logger log = LoggerFactory.getLogger(CarSalesTools.class);
    private final JdbcTemplate jdbc;

    public CarSalesTools(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 搜索车辆库存。LLM 判断用户提到了品牌/车型时自动调用。
     * 只返回上架且未删除的车源。
     *
     * @param query 用户原始输入或 LLM 提取的关键词
     */
    @org.springframework.ai.tool.annotation.Tool(description = """
        搜索公司车辆库存，返回匹配车源的详细信息（品牌、车系、车型、颜色、售价、能源类型等）。
        当用户询问具体品牌或车型时调用此工具。参数 query 可以是品牌名、车系名或用户原话。
        """)
    public String searchInventory(String query) {
        log.info("Tool调用: searchInventory('{}')", query);

        // 1. 提取数据库中所有品牌和车系名作为匹配词
        List<String> brands = jdbc.queryForList("SELECT DISTINCT brand_name FROM car_sku WHERE is_deleted = 0", String.class);
        List<String> series = jdbc.queryForList("SELECT DISTINCT series_name FROM car_sku WHERE is_deleted = 0", String.class);
        List<String> allTerms = new ArrayList<>();
        allTerms.addAll(brands);
        allTerms.addAll(series);
        allTerms.removeIf(t -> t == null || t.isBlank());
        allTerms.sort((a, b) -> Integer.compare(b.length(), a.length())); // 长词优先

        // 2. 检测匹配词
        List<String> matched = new ArrayList<>();
        for (String term : allTerms) {
            if (query.toLowerCase().contains(term.toLowerCase()) && !matched.contains(term)) {
                matched.add(term);
            }
            if (matched.size() >= 3) break;
        }

        // 3. 数据库查询（只查上架且未删除的车源）
        List<Map<String, Object>> rows;
        String baseSql = "SELECT * FROM car_sku WHERE sale_status = 1 AND is_deleted = 0 AND ";
        if (matched.isEmpty()) {
            rows = jdbc.queryForList(
                    baseSql + "(brand_name LIKE ? OR series_name LIKE ? OR model_name LIKE ?) LIMIT 15",
                    "%" + query + "%", "%" + query + "%", "%" + query + "%");
        } else {
            StringBuilder cond = new StringBuilder();
            List<Object> params = new ArrayList<>();
            for (int i = 0; i < matched.size(); i++) {
                if (i > 0) cond.append(" OR ");
                cond.append("(brand_name LIKE ? OR series_name LIKE ? OR model_name LIKE ?)");
                String p = "%" + matched.get(i) + "%";
                params.add(p); params.add(p); params.add(p);
            }
            rows = jdbc.queryForList(baseSql + "(" + cond + ")", params.toArray());
        }

        if (rows.isEmpty()) {
            return "库存中没有找到与「" + query + "」匹配的车源。";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("匹配到 ").append(rows.size()).append(" 条车源：\n");
        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> r = rows.get(i);
            sb.append(String.format("%d. %s | %s | 指导价%s | 全款%s | 金融%s | %s\n",
                    i + 1,
                    r.get("model_name"),
                    r.get("outer_color_name"),
                    r.get("guide_price"),
                    fenToWan(r.get("sale_price")),
                    fenToWan(r.get("sale_price_finance")),
                    energyType(r.get("energy_type"))));
            Object memo = r.get("memo");
            if (memo != null && !memo.toString().isBlank()) {
                sb.append("   备注: ").append(memo).append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * 获取所有门店信息
     */
    @org.springframework.ai.tool.annotation.Tool(description = """
        获取公司所有门店的地址、电话、营业时间。当用户问"你们在哪""门店地址""联系方式"时调用。
        """)
    public String getStoreInfo() {
        log.info("Tool调用: getStoreInfo()");
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM store_config WHERE is_active = 1 ORDER BY id");

        if (rows.isEmpty()) return "暂无门店信息。";

        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> r : rows) {
            sb.append(String.format("【%s】%s\n地址：%s\n电话：%s\n营业时间：%s\n\n",
                    r.get("store_name"), r.get("region"),
                    r.get("address"), r.get("phone"), r.get("working_hours")));
        }
        return sb.toString();
    }

    /**
     * 获取全库存摘要（用于推荐场景）
     */
    @org.springframework.ai.tool.annotation.Tool(description = """
        获取公司所有在售车源的摘要列表。当用户希望推荐车型但没指定具体品牌时调用，
        以便从库存中挑选合适的车推荐给用户。
        """)
    public String getAllCars() {
        log.info("Tool调用: getAllCars()");
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM car_sku WHERE sale_status = 1 AND is_deleted = 0 ORDER BY brand_name, series_name");

        if (rows.isEmpty()) return "当前没有在售车源。";

        StringBuilder sb = new StringBuilder();
        sb.append("当前在售车源（共 ").append(rows.size()).append(" 条）：\n");
        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> r = rows.get(i);
            sb.append(String.format("%d. %s | %s | 全款%s | %s\n",
                    i + 1,
                    r.get("model_name"),
                    r.get("outer_color_name"),
                    fenToWan(r.get("sale_price")),
                    energyType(r.get("energy_type"))));
        }
        return sb.toString();
    }

    /** 分 → 万 格式化（公司库金额单位为分），空值返回"价格待询" */
    private String fenToWan(Object fen) {
        if (fen == null) return "价格待询";
        double wan = ((Number) fen).doubleValue() / 1_000_000.0;
        return String.format("%.2f万", wan);
    }

    /** 能源类型：1-燃油车，2-新能源车（对齐公司库定义） */
    private String energyType(Object type) {
        if (type == null) return "未知能源";
        return ((Number) type).intValue() == 2 ? "新能源" : "燃油";
    }
}