package org.example.ai.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 数据库初始化器 —— 启动时建表 + 加载种子数据
 *
 * car_sku 表对齐公司真实车源表 aito_car_sku 的关键字段（navicat-db 导出），
 * 种子数据来自 scripts/parse_navicat_db.py 解析出的 data/car_sku.csv（47 条真实车源）。
 *
 * @Order(1)：必须先于 CarSkuVectorIndexer（向量化要读本表）执行
 */
@Component
@Order(1)
public class DatabaseInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DatabaseInitializer.class);
    private final JdbcTemplate jdbc;

    public DatabaseInitializer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(String... args) throws Exception {
        log.info("=== 开始初始化 SQLite 数据库 ===");

        createTables();

        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM car_sku", Integer.class);
        int before = count != null ? count : 0;
        // 每次启动都同步 CSV，用 INSERT OR IGNORE 只插入新增 ID
        seedCarSku();
        seedStores();
        seedStoreCarHot();
        int after = jdbc.queryForObject("SELECT COUNT(*) FROM car_sku", Integer.class);
        log.info("car_sku: 启动前 {} 条，启动后 {} 条（新增 {} 条）", before, after, after - before);

        // 实体映射每次启动都刷新（基于当前 car_sku 数据重建）
        rebuildEntityMapping();
    }

    private void createTables() {
        // 旧的假数据表（25 条编造车辆），已被真实车源表替代，清理掉
        jdbc.execute("DROP TABLE IF EXISTS inventory");

        // 车源 SKU 表（对齐公司 aito_car_sku 关键字段，金额单位：分）
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS car_sku (
                id                      INTEGER PRIMARY KEY,
                owner_name              TEXT,
                spec_name               TEXT,
                brand_name              TEXT,
                series_name             TEXT,
                model_name              TEXT,
                outer_color_name        TEXT,
                guide_price             TEXT,
                bare_car_price          INTEGER,
                settlement_price        INTEGER,
                sale_price              INTEGER,
                sale_price_finance      INTEGER,
                intended_landing_price  INTEGER,
                sku_tag                 TEXT,
                energy_type             INTEGER DEFAULT 1,
                is_virtual_source       INTEGER DEFAULT 0,
                in_store_insurance      INTEGER DEFAULT 1,
                can_issue_vat_invoice   INTEGER DEFAULT 1,
                sale_status             INTEGER DEFAULT 1,
                memo                    TEXT,
                sku_picture             TEXT,
                is_deleted              INTEGER DEFAULT 0
            )
        """);

        // 门店配置表
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS store_config (
                id              INTEGER PRIMARY KEY AUTOINCREMENT,
                store_name      TEXT    NOT NULL,
                store_code      TEXT    UNIQUE NOT NULL,
                address         TEXT    NOT NULL,
                phone           TEXT,
                working_hours   TEXT    DEFAULT '9:00-18:00',
                region          TEXT,
                latitude        REAL    DEFAULT 0,
                longitude       REAL    DEFAULT 0,
                is_active       INTEGER DEFAULT 1
            )
        """);

        // 旧版 store_config 表迁移：无经纬度列则添加（#10 ticket）
        try { jdbc.execute("ALTER TABLE store_config ADD COLUMN latitude REAL DEFAULT 0"); } catch (Exception ignored) {}
        try { jdbc.execute("ALTER TABLE store_config ADD COLUMN longitude REAL DEFAULT 0"); } catch (Exception ignored) {}

        // 向量同步日志表 — 追踪每条 SKU 的上次入库哈希（#2 决策10）
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS vector_sync_log (
                sku_id          INTEGER PRIMARY KEY,
                source_hash     TEXT    NOT NULL,
                last_synced_at  TEXT    DEFAULT (datetime('now','localtime'))
            )
        """);

        // 车系询问次数表 — 按周分桶存储（#2 决策11）
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS series_ask_count (
                id              INTEGER PRIMARY KEY AUTOINCREMENT,
                series_key      TEXT    NOT NULL,
                week_bucket     TEXT    NOT NULL,
                ask_count       INTEGER DEFAULT 0,
                UNIQUE(series_key, week_bucket)
            )
        """);

        // 实体归一化映射表 — 品牌/车系别名→统一实体ID（#1 决策3）
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS entity_mapping (
                entity_id       TEXT    NOT NULL,
                display_name    TEXT    NOT NULL,
                aliases_json    TEXT    DEFAULT '[]',
                PRIMARY KEY (entity_id, display_name)
            )
        """);

        // 文档同步日志表 — 追踪语料文件的上次入库哈希（#6 文档级增量）
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS doc_sync_log (
                source          TEXT    PRIMARY KEY,
                source_hash     TEXT    NOT NULL,
                last_synced_at  TEXT    DEFAULT (datetime('now','localtime'))
            )
        """);

        // 门店×车系热度表 — 门店维度销量排行（#11 ticket，决策7）
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS store_car_hot (
                id              INTEGER PRIMARY KEY AUTOINCREMENT,
                store_id        INTEGER NOT NULL REFERENCES store_config(id),
                series_name     TEXT    NOT NULL,
                sale_count      INTEGER DEFAULT 0,
                inquiry_count   INTEGER DEFAULT 0,
                stat_date       DATE    NOT NULL,
                UNIQUE(store_id, series_name, stat_date)
            )
        """);

        log.info("数据库表创建完成");
    }

    /** 从 classpath:data/car_sku.csv 加载 47 条真实车源 */
    private void seedCarSku() throws Exception {
        log.info("正在从 car_sku.csv 加载车源数据...");

        String sql = """
            INSERT OR IGNORE INTO car_sku (id, owner_name, spec_name, brand_name, series_name, model_name,
            outer_color_name, guide_price, bare_car_price, settlement_price, sale_price,
            sale_price_finance, intended_landing_price, sku_tag, energy_type, is_virtual_source,
            in_store_insurance, can_issue_vat_invoice, sale_status, memo, sku_picture, is_deleted)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;

        int inserted = 0;
        ClassPathResource resource = new ClassPathResource("data/car_sku.csv");
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            String header = reader.readLine(); // 跳过表头
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                List<String> cols = parseCsvLine(line);
                jdbc.update(sql,
                        parseLong(cols.get(0)),           // id
                        cols.get(1),                      // owner_name
                        cols.get(2),                      // spec_name
                        cols.get(3),                      // brand_name
                        cols.get(4),                      // series_name
                        cols.get(5),                      // model_name
                        cols.get(6),                      // outer_color_name
                        cols.get(7),                      // guide_price
                        parseLong(cols.get(8)),           // bare_car_price
                        parseLong(cols.get(9)),           // settlement_price
                        parseLong(cols.get(10)),          // sale_price
                        parseLong(cols.get(11)),          // sale_price_finance
                        parseLong(cols.get(12)),          // intended_landing_price
                        cols.get(13),                     // sku_tag
                        parseInt(cols.get(14), 1),        // energy_type
                        parseInt(cols.get(15), 0),        // is_virtual_source
                        parseInt(cols.get(16), 1),        // in_store_insurance
                        parseInt(cols.get(17), 1),        // can_issue_vat_invoice
                        parseInt(cols.get(18), 1),        // sale_status
                        cols.get(19),                     // memo
                        cols.get(20),                     // sku_picture
                        parseInt(cols.get(21), 0));       // is_deleted
                inserted++;
            }
        }
        log.info("已加载 {} 条车源记录", inserted);
    }

    private void seedStores() {
        log.info("正在同步门店数据...");

        String sql = """
            INSERT OR IGNORE INTO store_config (store_name, store_code, address, phone, working_hours, region, latitude, longitude, is_active)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;

        Object[][] stores = {
            {"朝阳旗舰店",  "STORE-CY-001", "北京市朝阳区建国路88号",        "010-88886666", "9:00-20:00", "朝阳区", 39.9087, 116.4716, 1},
            {"海淀体验中心", "STORE-HD-002", "北京市海淀区中关村大街15号",    "010-66668888", "9:00-19:00", "海淀区", 39.9836, 116.3168, 1},
            {"浦东城市展厅", "STORE-PD-003", "上海市浦东新区陆家嘴环路958号",  "021-55551111", "9:00-21:00", "浦东新区", 31.2357, 121.5065, 1},
            {"深圳科技园店", "STORE-SZ-004", "深圳市南山区科技南路18号",       "0755-33332222", "9:00-20:00", "南山区", 22.5370, 113.9550, 1},
            {"成都锦江店",  "STORE-CD-005", "成都市锦江区红星路三段99号",     "028-88889999", "9:00-18:00", "锦江区", 30.6535, 104.0826, 1},
            {"广州天河体验店","STORE-GZ-006", "广州市天河区天河路385号",       "020-22221111", "9:00-21:00", "天河区", 23.1338, 113.3276, 1},
        };

        for (Object[] store : stores) {
            jdbc.update(sql, store);
        }
        // 旧数据库迁移：已存在的门店 INSERT OR IGNORE 会跳过，需 UPDATE 补经纬度（#10 ticket）
        jdbc.update("UPDATE store_config SET latitude = ?, longitude = ? WHERE store_code = ? AND latitude = 0",
                39.9087, 116.4716, "STORE-CY-001");
        jdbc.update("UPDATE store_config SET latitude = ?, longitude = ? WHERE store_code = ? AND latitude = 0",
                39.9836, 116.3168, "STORE-HD-002");
        jdbc.update("UPDATE store_config SET latitude = ?, longitude = ? WHERE store_code = ? AND latitude = 0",
                31.2357, 121.5065, "STORE-PD-003");
        jdbc.update("UPDATE store_config SET latitude = ?, longitude = ? WHERE store_code = ? AND latitude = 0",
                22.5370, 113.9550, "STORE-SZ-004");
        jdbc.update("UPDATE store_config SET latitude = ?, longitude = ? WHERE store_code = ? AND latitude = 0",
                30.6535, 104.0826, "STORE-CD-005");
        jdbc.update("UPDATE store_config SET latitude = ?, longitude = ? WHERE store_code = ? AND latitude = 0",
                23.1338, 113.3276, "STORE-GZ-006");
        log.info("已插入 {} 条门店记录", stores.length);
    }

    /** 门店×车系热度种子数据（#11 ticket，决策7）。每次启动 UPSERT 确保更新不会丢。 */
    private void seedStoreCarHot() {
        log.info("正在同步门店热度数据...");

        String sql = """
            INSERT OR IGNORE INTO store_car_hot (store_id, series_name, sale_count, inquiry_count, stat_date)
            VALUES (?, ?, ?, ?, date('now'))
        """;

        // 北京朝阳旗舰店 — 偏好比亚迪+特斯拉+理想
        Object[][] cy = {
            {1, "宋PLUS DM-i", 28, 45}, {1, "海鸥", 22, 38}, {1, "Model Y", 18, 30},
            {1, "理想L6", 15, 25}, {1, "汉EV", 12, 20},
        };
        // 北京海淀体验中心 — 偏好特斯拉+蔚来+小鹏
        Object[][] hd = {
            {2, "Model 3", 25, 40}, {2, "Model Y", 20, 35}, {2, "蔚来ET5", 18, 28},
            {2, "小鹏P7", 14, 22}, {2, "宋PLUS DM-i", 10, 18},
        };
        // 上海浦东展厅 — 偏好特斯拉+奥迪+宝马
        Object[][] pd = {
            {3, "Model Y", 30, 50}, {3, "奥迪A4L", 22, 35}, {3, "宝马X3 M", 16, 25},
            {3, "奥迪Q5L", 14, 22}, {3, "理想L7", 12, 20},
        };
        // 深圳科技园店 — 偏好比亚迪+问界+极氪
        Object[][] sz = {
            {4, "宋PLUS DM-i", 32, 48}, {4, "汉EV", 25, 40}, {4, "问界M7", 20, 35},
            {4, "海豹", 15, 25}, {4, "极氪001", 10, 18},
        };
        // 成都锦江店 — 偏好吉利+长安+理想
        Object[][] cd = {
            {5, "理想L6", 22, 35}, {5, "吉利星越L", 18, 28}, {5, "长安启源A07", 12, 20},
            {5, "领克08 EM-P", 10, 16},
        };
        // 广州天河体验店 — 偏好丰田+本田+大众
        Object[][] gz = {
            {6, "凯美瑞", 20, 32}, {6, "大众朗逸", 16, 25}, {6, "RAV4荣放", 14, 22},
            {6, "宋PLUS DM-i", 12, 20}, {6, "理想L6", 10, 16},
        };

        Object[][][] all = {cy, hd, pd, sz, cd, gz};
        int total = 0;
        for (Object[][] store : all) {
            for (Object[] row : store) {
                jdbc.update(sql, row);
                total++;
            }
        }
        log.info("已插入 {} 条门店热度记录", total);
    }

    /**
     * 从 car_sku 的去重 brand_name + series_name 构建 entity_mapping。
     * 每次启动重建——新增车系无需改代码，重启即生效。
     *
     * <p>public：车源变更广播（SkuVectorUpdater）处理后也需调用，
     * 使新车系无需重启即可进入实体解析/关键词表。</p>
     *
     * 手动别名覆盖常见变体：品牌缩写、中英文、大小写、口语简称。
     */
    public void rebuildEntityMapping() {
        log.info("正在构建实体映射表...");

        // 清空重建（幂等）
        jdbc.update("DELETE FROM entity_mapping");

        // 从 car_sku 提取所有唯一的 brand_name + series_name 组合（仅在售——
        // 下架车系不进实体映射，避免别名/关键词链路宣称无库存车系存在）
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT DISTINCT brand_name, series_name FROM car_sku WHERE is_deleted = 0 AND sale_status = 1 ORDER BY brand_name, series_name");

        String insertSql = "INSERT INTO entity_mapping (entity_id, display_name, aliases_json) VALUES (?, ?, ?)";
        int inserted = 0;

        for (Map<String, Object> row : rows) {
            String brand = String.valueOf(row.get("brand_name"));
            String series = String.valueOf(row.get("series_name"));
            if ("null".equals(brand) || brand.isBlank() || "null".equals(series) || series.isBlank()) continue;

            String entityId = "entity:car:" + toSlug(brand) + ":" + toSlug(series);
            String displayName = brand + "-" + series;

            // 生成常见别名
            String aliasesJson = buildAliases(brand, series);

            jdbc.update(insertSql, entityId, displayName, aliasesJson);
            inserted++;
        }

        log.info("实体映射表构建完成，共 {} 条", inserted);
    }

    /** 品牌/车系名 → 小写连字符 slug */
    private String toSlug(String s) {
        return s.trim().toLowerCase()
                .replaceAll("[^a-z0-9\\u4e00-\\u9fff]+", "-")
                .replaceAll("^-|-$", "");
    }

    /** 生成常见别名的 JSON 数组 */
    private String buildAliases(String brand, String series) {
        List<String> aliases = new ArrayList<>();

        // 中英文互转
        Map<String, String> enToZh = Map.ofEntries(
                Map.entry("byd", "比亚迪"),
                Map.entry("nio", "蔚来"),
                Map.entry("xpeng", "小鹏"),
                Map.entry("li auto", "理想"),
                Map.entry("zeekr", "极氪"),
                Map.entry("lynk", "领克"),
                Map.entry("tesla", "特斯拉"),
                Map.entry("toyota", "丰田"),
                Map.entry("honda", "本田"),
                Map.entry("bmw", "宝马"),
                Map.entry("benz", "奔驰"),
                Map.entry("audi", "奥迪"),
                Map.entry("porsche", "保时捷"),
                Map.entry("nissan", "日产"),
                Map.entry("vw", "大众"),
                Map.entry("volkswagen", "大众"),
                Map.entry("changan", "长安"),
                Map.entry("gwm", "长城"),
                Map.entry("chery", "奇瑞"),
                Map.entry("geely", "吉利"),
                Map.entry("aito", "问界")
        );

        String brandSlug = toSlug(brand);
        for (var entry : enToZh.entrySet()) {
            if (brandSlug.contains(entry.getKey())) {
                aliases.add(entry.getValue() + "-" + series);
            }
        }

        // 常见车系别名
        Map<String, String> seriesAliases = Map.ofEntries(
                Map.entry("宋plus", "宋PLUS"),
                Map.entry("宋pro", "宋Pro"),
                Map.entry("秦plus", "秦PLUS"),
                Map.entry("秦l", "秦L"),
                Map.entry("汉ev", "汉EV"),
                Map.entry("唐dm-i", "唐DM-i"),
                Map.entry("海鸥", "海鸥"),
                Map.entry("海豹", "海豹"),
                Map.entry("model3", "Model 3"),
                Map.entry("model y", "Model Y"),
                Map.entry("et5", "ET5"),
                Map.entry("es6", "ES6"),
                Map.entry("p7", "P7"),
                Map.entry("g6", "G6"),
                Map.entry("g9", "G9"),
                Map.entry("l6", "L6"),
                Map.entry("l7", "L7"),
                Map.entry("l8", "L8"),
                Map.entry("l9", "L9"),
                Map.entry("m5", "M5"),
                Map.entry("m7", "M7"),
                Map.entry("m9", "M9")
        );

        String seriesSlug = toSlug(series);
        for (var entry : seriesAliases.entrySet()) {
            if (seriesSlug.contains(toSlug(entry.getKey()))) {
                aliases.add(brand + "-" + entry.getValue());
            }
        }

        // 裸车系名兜底别名：未经预置映射的新车系（运行时广播进来的）
        // 也能在整句提问中被 EntityResolver 命中（Layer-1 子串匹配）
        if (series.trim().length() >= 2) {
            aliases.add(series.trim());
        }

        // 品牌缩写别名（取品牌首字母缩写或常用简称）
        String shortBrand = brand.replaceAll("[^a-zA-Z\\u4e00-\\u9fff]", "");
        if (!shortBrand.equals(brand)) {
            aliases.add(shortBrand + "-" + series);
        }

        // #30：裸车型词拆词别名——车系名去品牌前缀后按空格/连字符切 token，
        // 长度 ≥2 且含字母的 token 注册为独立别名（"宝马X3 M" → "X3"）。
        // 让"我想买x3"这类口语代号命中实体；撞词由多实体分级逻辑（FAMILY/BRAND）消化。
        String strippedSeries = series.trim();
        if (strippedSeries.startsWith(brand)) {
            strippedSeries = strippedSeries.substring(brand.length()).trim();
        }
        for (String token : strippedSeries.split("[\\s\\-]+")) {
            if (token.length() >= 2 && containsLetter(token) && !aliases.contains(token)) {
                aliases.add(token);
            }
        }

        return aliases.isEmpty() ? "[]" : "[\"" + String.join("\",\"", aliases) + "\"]";
    }

    /** token 是否含字母（Unicode 字母，含中文）——挡掉纯数字/符号 token */
    private static boolean containsLetter(String s) {
        return s.chars().anyMatch(Character::isLetter);
    }

    /** 极简 CSV 解析（处理引号包裹和 "" 转义） */
    private List<String> parseCsvLine(String line) {
        List<String> cols = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cur.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    cur.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == ',') {
                cols.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        cols.add(cur.toString());
        return cols;
    }

    private Long parseLong(String s) {
        if (s == null || s.isBlank()) return null;
        return Long.parseLong(s.trim());
    }

    private Integer parseInt(String s, int defaultValue) {
        if (s == null || s.isBlank()) return defaultValue;
        return Integer.parseInt(s.trim());
    }
}