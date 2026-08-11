package org.example.ai.impl.tool;

import org.example.ai.config.DynamicKeywordBuilder;
import org.example.ai.impl.location.GeoLocation;
import org.example.ai.impl.location.HotCar;
import org.example.ai.impl.location.HotCarRepository;
import org.example.ai.impl.location.PositionStackGeoLocator;
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

    /** 防止模型误调用时把全量 SKU 回流上下文。 */
    private static final int SEARCH_MATCHED_LIMIT = 30;
    /** 全库存摘要上限：只给模型"心里有数"的最小信息，避免 30 条车源诱导超量推荐。 */
    private static final int ALL_CARS_LIMIT = 15;
    private final JdbcTemplate jdbc;
    private final StoreLocator storeLocator;
    private final PositionStackGeoLocator geocoder;
    private final DynamicKeywordBuilder keywordBuilder;
    private final HotCarRepository hotCarRepo;

    public CarSalesTools(JdbcTemplate jdbc, StoreLocator storeLocator, PositionStackGeoLocator geocoder,
                         DynamicKeywordBuilder keywordBuilder, HotCarRepository hotCarRepo) {
        this.jdbc = jdbc;
        this.storeLocator = storeLocator;
        this.geocoder = geocoder;
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
        搜索公司车辆库存，返回匹配车源的详细信息（品牌、车系、车型、颜色、价格、能源类型等）。
        仅在客户询问具体品牌/车型、或明确问价格/配置/库存时调用此工具。
        客户只是泛泛求推荐（没说具体车、没问价）时不要调用，用 getAllCars 即可。
        参数 query 可以是品牌名、车系名或用户原话。
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
            rows = jdbc.queryForList(
                    baseSql + "(" + cond + ") LIMIT " + SEARCH_MATCHED_LIMIT, params.toArray());
        }

        if (rows.isEmpty()) {
            return "库存中没有找到与「" + query + "」匹配的车源。";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("匹配到 ").append(rows.size()).append(" 条车源：\n");
        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> r = rows.get(i);
            // 客户视角：通俗车系名（品牌+车系）+ 单一价格（指导价），不输出完整款型名
            Object price = r.get("guide_price");
            sb.append(String.format("%d. %s %s | %s | 价格%s | %s\n",
                    i + 1,
                    r.get("brand_name"),
                    r.get("series_name"),
                    r.get("outer_color_name"),
                    price == null || price.toString().isBlank() ? "待询" : price,
                    energyType(r.get("energy_type"))));
            Object memo = r.get("memo");
            if (memo != null && !memo.toString().isBlank()) {
                sb.append("   备注: ").append(memo).append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * 按城市返回距离最近的一家门店（#16 ticket）。
     * region LIKE 命中优先；未命中走 positionstack 地理编码 → Haversine 最近门店兜底；仍无则提示未找到。
     * 只返回一家门店（微信短消息约束）。
     */
    @org.springframework.ai.tool.annotation.Tool(description = """
        按城市返回距离最近的一家门店的地址、电话、营业时间。
        当用户问"你们在哪""门店地址""最近的店""联系方式"时调用。
        必须传入用户所在城市 city；如果还不知道用户城市，先反问用户所在城市，不要凭空猜测。
        只返回最近的一家门店，不返回列表。
        """)
    public String getStoreInfo(String city) {
        log.info("Tool调用: getStoreInfo('{}')", city);

        if (city == null || city.isBlank()) {
            return "请先告诉我您所在的城市，我帮您查最近的门店～";
        }

        // 1. region LIKE 命中 → 返回第一家
        StoreInfo regionMatch = storeLocator.findByRegion(city);
        if (regionMatch != null) {
            return formatStore(regionMatch);
        }

        // 2. positionstack 地理编码兜底 → 最近门店
        GeoLocation loc = geocoder.locateByCity(city);
        if (loc != null) {
            StoreInfo nearest = storeLocator.findNearest(loc.lat(), loc.lng());
            if (nearest != null) {
                return formatStore(nearest);
            }
        }

        // 3. 仍无
        return "未找到该城市的门店，请确认城市名";
    }

    /** 单店展示格式（#16：只返回一家）。 */
    private String formatStore(StoreInfo s) {
        return String.format("离您最近的是【%s】%s\n地址：%s\n电话：%s\n营业时间：%s",
                s.storeName(), s.region(), s.address(), s.phone(), s.workingHours());
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
        获取公司所有在售车源的摘要列表（仅车系名 + 能源类型，不含价格、不含颜色）。
        当用户希望推荐车型但没指定具体品牌时调用，用于心里有数、挑选合适的车系推荐。
        注意：本工具不返回价格——客户没问价就绝不能报价格；客户问价/要具体车源细节时用 searchInventory。
        """)
    public String getAllCars() {
        log.info("Tool调用: getAllCars()");
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM car_sku WHERE sale_status = 1 AND is_deleted = 0 "
                        + "ORDER BY brand_name, series_name LIMIT " + ALL_CARS_LIMIT);

        if (rows.isEmpty()) return "当前没有在售车源。";

        StringBuilder sb = new StringBuilder();
        sb.append("当前在售车系摘要（最多返回 ").append(ALL_CARS_LIMIT)
                .append(" 条，仅车系级概括，不含价格与颜色）：\n");
        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> r = rows.get(i);
            sb.append(String.format("%d. %s %s（%s）\n",
                    i + 1,
                    r.get("brand_name"),
                    r.get("series_name"),
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