package org.example.ai.location;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.io.File;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HotCarRepository 集成测试（#11 ticket）。
 */
class HotCarRepositoryTest {

    private File tmpDb;
    private JdbcTemplate jdbc;
    private HotCarRepository repo;

    @BeforeEach
    void setUp() {
        tmpDb = new File(System.getProperty("java.io.tmpdir"), "test-hot-" + UUID.randomUUID() + ".db");
        var ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + tmpDb.getAbsolutePath());
        jdbc = new JdbcTemplate(ds);

        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS store_car_hot (
                id              INTEGER PRIMARY KEY AUTOINCREMENT,
                store_id        INTEGER NOT NULL,
                series_name     TEXT    NOT NULL,
                sale_count      INTEGER DEFAULT 0,
                inquiry_count   INTEGER DEFAULT 0,
                stat_date       DATE    NOT NULL,
                UNIQUE(store_id, series_name, stat_date)
            )
        """);

        repo = new HotCarRepository(jdbc);
    }

    @AfterEach
    void tearDown() {
        if (tmpDb != null && tmpDb.exists()) tmpDb.delete();
    }

    // ---- 排序 ----

    @Test
    @DisplayName("按综合热度降序排列")
    void sortedByTotalHeat() {
        jdbc.update("INSERT INTO store_car_hot (store_id, series_name, sale_count, inquiry_count, stat_date) VALUES (1,'汉EV',10,5,date('now'))");
        jdbc.update("INSERT INTO store_car_hot (store_id, series_name, sale_count, inquiry_count, stat_date) VALUES (1,'宋PLUS',25,15,date('now'))");
        jdbc.update("INSERT INTO store_car_hot (store_id, series_name, sale_count, inquiry_count, stat_date) VALUES (1,'海鸥',15,10,date('now'))");

        List<HotCar> hot = repo.getHotCars(1);
        assertThat(hot).hasSize(3);
        assertThat(hot.get(0).seriesName()).isEqualTo("宋PLUS");  // total 40
        assertThat(hot.get(1).seriesName()).isEqualTo("海鸥");     // total 25
        assertThat(hot.get(2).seriesName()).isEqualTo("汉EV");     // total 15
    }

    // ---- topN ----

    @Test
    @DisplayName("topN 截断正确")
    void topNLimitsResults() {
        jdbc.update("INSERT INTO store_car_hot (store_id, series_name, sale_count, inquiry_count, stat_date) VALUES (1,'A',5,0,date('now'))");
        jdbc.update("INSERT INTO store_car_hot (store_id, series_name, sale_count, inquiry_count, stat_date) VALUES (1,'B',4,0,date('now'))");
        jdbc.update("INSERT INTO store_car_hot (store_id, series_name, sale_count, inquiry_count, stat_date) VALUES (1,'C',3,0,date('now'))");
        jdbc.update("INSERT INTO store_car_hot (store_id, series_name, sale_count, inquiry_count, stat_date) VALUES (1,'D',2,0,date('now'))");
        jdbc.update("INSERT INTO store_car_hot (store_id, series_name, sale_count, inquiry_count, stat_date) VALUES (1,'E',1,0,date('now'))");

        List<HotCar> top3 = repo.getHotCars(1, 3);
        assertThat(top3).hasSize(3);
        assertThat(top3.get(0).seriesName()).isEqualTo("A");
        assertThat(top3.get(1).seriesName()).isEqualTo("B");
        assertThat(top3.get(2).seriesName()).isEqualTo("C");
    }

    // ---- 空 ----

    @Test
    @DisplayName("空门店返回空列表")
    void emptyStoreReturnsEmpty() {
        List<HotCar> hot = repo.getHotCars(999);
        assertThat(hot).isEmpty();
    }

    @Test
    @DisplayName("不同门店数据隔离")
    void storeIsolation() {
        jdbc.update("INSERT INTO store_car_hot (store_id, series_name, sale_count, inquiry_count, stat_date) VALUES (1,'宋PLUS',30,20,date('now'))");
        jdbc.update("INSERT INTO store_car_hot (store_id, series_name, sale_count, inquiry_count, stat_date) VALUES (2,'Model Y',25,15,date('now'))");

        assertThat(repo.getHotCars(1)).hasSize(1);
        assertThat(repo.getHotCars(2)).hasSize(1);
        assertThat(repo.getHotCars(1).get(0).seriesName()).isEqualTo("宋PLUS");
        assertThat(repo.getHotCars(2).get(0).seriesName()).isEqualTo("Model Y");
    }
}