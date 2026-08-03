package org.example.ai.impl.tool;

import org.example.ai.config.DynamicKeywordBuilder;
import org.example.ai.impl.location.GeoLocator;
import org.example.ai.impl.location.HotCar;
import org.example.ai.impl.location.HotCarRepository;
import org.example.ai.impl.location.StoreInfo;
import org.example.ai.impl.location.StoreLocator;
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
    private final StoreLocator storeLocator;
    private final GeoLocator geoLocator;
    private final DynamicKeywordBuilder keywordBuilder;
    private final HotCarRepository hotCarRepo;

    public CarSalesTools(JdbcTemplate jdbc, StoreLocator storeLocator, GeoLocator geoLocator,
                         DynamicKeywordBuilder keywordBuilder, HotCarRepository hotCarRepo) {
        this.jdbc = jdbc;
        this.storeLocator = storeLocator;
        this.geoLocator = geoLocator;
        this.keywordBuilder = keywordBuilder;
        this.hotCarRepo = hotCarRepo;
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

        // 从动态关键词表提取匹配的品牌/车系名（#12 ticket，替代每次查库）
        List<String> matched = keywordBuilder.extractKeywords(query);
        if (matched.size() > 3) matched = matched.subList(0, 3);

        // 数据库查询（只查上架且未删除的车源）
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
     * 获取门店信息（按用户位置距离排序，最近的门店排前面）。
     */
    @org.springframework.ai.tool.annotation.Tool(description = """
        获取公司所有门店的地址、电话、营业时间。当用户问"你们在哪""门店地址""联系方式"时调用。
        返回按距离排序的门店列表，最近门店排在最前面。
        """)
    public String getStoreInfo() {
        log.info("Tool调用: getStoreInfo()");

        var loc = geoLocator.locate(null);
        List<StoreInfo> stores;
        if (loc != null) {
            stores = storeLocator.findAllWithDistance(loc.lat(), loc.lng());
        } else {
            // 无法定位时回退到原始顺序
            stores = storeLocator.findAllActiveRaw();
        }

        if (stores.isEmpty()) return "暂无门店信息。";

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < stores.size(); i++) {
            StoreInfo s = stores.get(i);
            double km = loc != null
                    ? StoreLocator.haversineKm(loc.lat(), loc.lng(), s.latitude(), s.longitude())
                    : 0;
            sb.append(String.format("【%s】%s %s\n地址：%s\n电话：%s\n营业时间：%s",
                    s.storeName(), s.region(),
                    km > 0 ? String.format("（距您约 %.1f 公里）", km) : "",
                    s.address(), s.phone(), s.workingHours()));
            sb.append("\n\n");
        }
        return sb.toString();
    }

    /**
     * 获取指定门店的热门车系排行（#11 ticket，决策7）。
     */
    @org.springframework.ai.tool.annotation.Tool(description = """
        获取指定门店的热门车系排行榜。当用户问"你们店什么车卖得好""最近什么车热门""推荐一款"时调用。
        参数 storeId 为门店ID（1-6），不传则查所有门店汇总热度。
        返回车系名、销量、咨询量。
        """)
    public String getHotCars(Integer storeId) {
        log.info("Tool调用: getHotCars({})", storeId);
        List<HotCar> hot;
        if (storeId != null && storeId > 0) {
            hot = hotCarRepo.getHotCars(storeId, 5);
        } else {
            // 查第1家门店作为默认
            hot = hotCarRepo.getHotCars(1, 5);
        }

        if (hot.isEmpty()) return "暂无该门店的热度数据。";

        StringBuilder sb = new StringBuilder();
        sb.append("热门车系排行：\n");
        for (int i = 0; i < hot.size(); i++) {
            HotCar h = hot.get(i);
            sb.append(String.format("%d. %s — 销量 %d 台，咨询 %d 次\n",
                    i + 1, h.seriesName(), h.saleCount(), h.inquiryCount()));
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