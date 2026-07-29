package org.example.ai.location;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 门店定位器 —— Haversine 公式计算最近门店（#10 ticket，决策6）。
 *
 * 上线即正确，不需要外部依赖。注入 JdbcTemplate 读 store_config 表。
 */
@Component
public class StoreLocator {

    private static final Logger log = LoggerFactory.getLogger(StoreLocator.class);

    private static final double EARTH_RADIUS_KM = 6371.0;

    private final JdbcTemplate jdbc;

    public StoreLocator(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 返回离给定坐标最近的一家门店。
     *
     * @param lat 用户纬度
     * @param lng 用户经度
     * @return 最近门店，无活跃门店时返回 null
     */
    public StoreInfo findNearest(double lat, double lng) {
        List<StoreInfo> all = findAllActive();
        if (all.isEmpty()) return null;
        return all.stream()
                .min(Comparator.comparingDouble(s -> haversineKm(lat, lng, s.latitude(), s.longitude())))
                .orElse(null);
    }

    /**
     * 返回离给定坐标最近的 topN 家门店，按距离升序排列。
     *
     * @param lat  用户纬度
     * @param lng  用户经度
     * @param topN 返回数量
     * @return 最近的门店列表（最多 topN 条），无活跃门店时返回空列表
     */
    public List<StoreInfo> findNearest(double lat, double lng, int topN) {
        List<StoreInfo> all = new ArrayList<>(findAllActive());
        all.sort(Comparator.comparingDouble(s -> haversineKm(lat, lng, s.latitude(), s.longitude())));
        return all.subList(0, Math.min(topN, all.size()));
    }

    /**
     * 返回全部活跃门店，含距离（用于 getStoreInfo 工具按距离排序展示）。
     */
    public List<StoreInfo> findAllWithDistance(double lat, double lng) {
        List<StoreInfo> all = new ArrayList<>(findAllActive());
        all.sort(Comparator.comparingDouble(s -> haversineKm(lat, lng, s.latitude(), s.longitude())));
        return all;
    }

    /**
     * 返回全部活跃门店（无距离排序，用于 GeoLocator 不可用时的回退）。
     */
    public List<StoreInfo> findAllActiveRaw() {
        return new ArrayList<>(findAllActive());
    }

    /**
     * Haversine 公式：两点间球面距离（km）。
     */
    public static double haversineKm(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                  * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return EARTH_RADIUS_KM * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    // ---- 内部 ----

    private List<StoreInfo> findAllActive() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM store_config WHERE is_active = 1 ORDER BY id");
        List<StoreInfo> stores = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            stores.add(new StoreInfo(
                    ((Number) r.get("id")).intValue(),
                    str(r.get("store_name")),
                    str(r.get("store_code")),
                    str(r.get("address")),
                    str(r.get("phone")),
                    str(r.get("working_hours")),
                    str(r.get("region")),
                    toDouble(r.get("latitude")),
                    toDouble(r.get("longitude")),
                    ((Number) r.get("is_active")).intValue() == 1
            ));
        }
        return stores;
    }

    private String str(Object o) { return o == null ? "" : o.toString(); }

    private double toDouble(Object o) {
        if (o == null) return 0.0;
        return ((Number) o).doubleValue();
    }
}