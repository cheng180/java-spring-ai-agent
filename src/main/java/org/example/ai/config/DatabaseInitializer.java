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
        if (count != null && count > 0) {
            log.info("car_sku 已有 {} 条车源记录，跳过数据初始化", count);
        } else {
            seedCarSku();
            seedStores();
            log.info("种子数据插入完成");
        }
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
                is_active       INTEGER DEFAULT 1
            )
        """);

        log.info("数据库表创建完成");
    }

    /** 从 classpath:data/car_sku.csv 加载 47 条真实车源 */
    private void seedCarSku() throws Exception {
        log.info("正在从 car_sku.csv 加载车源数据...");

        String sql = """
            INSERT INTO car_sku (id, owner_name, spec_name, brand_name, series_name, model_name,
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
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM store_config", Integer.class);
        if (count != null && count > 0) {
            log.info("门店数据已存在（{} 条），跳过", count);
            return;
        }
        log.info("正在插入门店数据...");

        Object[][] stores = {
            {"朝阳旗舰店", "STORE-CY-001", "北京市朝阳区建国路88号", "010-88886666", "9:00-20:00", "朝阳区", 1},
            {"海淀体验中心", "STORE-HD-002", "北京市海淀区中关村大街15号", "010-66668888", "9:00-19:00", "海淀区", 1},
            {"浦东城市展厅", "STORE-PD-003", "上海市浦东新区陆家嘴环路958号", "021-55551111", "9:00-21:00", "浦东新区", 1},
            {"深圳科技园店", "STORE-SZ-004", "深圳市南山区科技南路18号", "0755-33332222", "9:00-20:00", "南山区", 1},
            {"成都锦江店", "STORE-CD-005", "成都市锦江区红星路三段99号", "028-88889999", "9:00-18:00", "锦江区", 1},
            {"广州天河体验店", "STORE-GZ-006", "广州市天河区天河路385号", "020-22221111", "9:00-21:00", "天河区", 1},
        };

        String sql = """
            INSERT INTO store_config (store_name, store_code, address, phone, working_hours, region, is_active)
            VALUES (?, ?, ?, ?, ?, ?, ?)
        """;

        for (Object[] store : stores) {
            jdbc.update(sql, store);
        }
        log.info("已插入 {} 条门店记录", stores.length);
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