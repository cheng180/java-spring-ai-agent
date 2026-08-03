package org.example.ai.impl.location;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 门店热度仓库 —— 门店×车系销量排行（#11 ticket，决策7）。
 *
 * 读 store_car_hot 表，按门店维度返回热门车系。
 * 首版假数据，后期对接真实销售数据库。
 */
@Component
public class HotCarRepository {

    private static final Logger log = LoggerFactory.getLogger(HotCarRepository.class);

    private final JdbcTemplate jdbc;

    public HotCarRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 指定门店的热门车系（综合热度降序）。
     *
     * @param storeId 门店 ID
     * @return 该门店全部热门车系，按 totalHeat 降序排列
     */
    public List<HotCar> getHotCars(int storeId) {
        return getHotCars(storeId, Integer.MAX_VALUE);
    }

    /**
     * 指定门店的 topN 热门车系。
     *
     * @param storeId 门店 ID
     * @param topN    返回条数上限
     * @return 热度 topN 车系，按综合热度降序
     */
    public List<HotCar> getHotCars(int storeId, int topN) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT series_name, SUM(sale_count) as total_sale, SUM(inquiry_count) as total_inquiry " +
                "FROM store_car_hot WHERE store_id = ? " +
                "GROUP BY series_name ORDER BY total_sale + total_inquiry DESC LIMIT ?",
                storeId, topN);

        List<HotCar> cars = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            cars.add(new HotCar(
                    str(r.get("series_name")),
                    ((Number) r.get("total_sale")).intValue(),
                    ((Number) r.get("total_inquiry")).intValue()
            ));
        }
        if (cars.isEmpty()) {
            log.debug("HotCarRepository: 门店 {} 无热度数据", storeId);
        }
        return cars;
    }

    private String str(Object o) { return o == null ? "" : o.toString(); }
}