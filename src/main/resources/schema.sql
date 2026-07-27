-- ==========================================
-- SQLite 数据库表结构（参考文档）
-- 实际初始化由 DatabaseInitializer.java 执行
-- ==========================================

-- 车辆库存表
CREATE TABLE IF NOT EXISTS inventory (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    stock_code      TEXT    NOT NULL UNIQUE,   -- 库存编号 (如 BYD-001)
    brand           TEXT    NOT NULL,          -- 品牌
    model           TEXT    NOT NULL,          -- 车型
    variant         TEXT,                      -- 具体配置款
    year            INTEGER,                   -- 年款
    price_guide     REAL,                      -- 指导价（万元）
    price_sale      REAL,                      -- 实际售价（万元）
    stock           INTEGER DEFAULT 0,         -- 库存数量
    monthly_sales   INTEGER DEFAULT 0,         -- 月销量
    popularity      REAL    DEFAULT 0,         -- 热度评分 0-10
    fuel_type       TEXT,                      -- 能源类型（纯电/插混/增程）
    color           TEXT,                      -- 可选颜色
    category        TEXT,                      -- 分类（轿车/SUV/MPV）
    description     TEXT,                      -- 车辆简介
    created_at      TEXT    DEFAULT (datetime('now','localtime'))
);

-- 门店配置表
CREATE TABLE IF NOT EXISTS store_config (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    store_name      TEXT    NOT NULL,          -- 门店名称
    store_code      TEXT    UNIQUE NOT NULL,   -- 仓店编码
    address         TEXT    NOT NULL,          -- 地址
    phone           TEXT,                      -- 门店电话
    working_hours   TEXT    DEFAULT '9:00-18:00', -- 营业时间
    region          TEXT,                      -- 覆盖区域
    is_active       INTEGER DEFAULT 1          -- 是否启用 (1=启用, 0=停用)
);