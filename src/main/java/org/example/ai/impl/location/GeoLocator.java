package org.example.ai.impl.location;

/**
 * 地理位置解析接口（#10 ticket，决策6；#16 扩展城市地理编码）。
 *
 * 两种能力：
 * - {@link #locate(String)}：IP → 经纬度（FixedGeoLocator 当前返回固定坐标，IP 定位 #16 out-of-scope）。
 * - {@link #locateByCity(String)}：城市名 → 经纬度（positionstack 地理编码，#16 门店查询兜底用）。
 */
public interface GeoLocator {

    /**
     * 根据用户 IP 返回地理位置。
     *
     * @param userIp 用户 IP 地址（首版假数据不实际使用此参数）
     * @return 经纬度 + 城市名，解析失败返回 null
     */
    GeoLocation locate(String userIp);

    /**
     * 根据城市名返回经纬度（地理编码，#16 ticket）。
     *
     * 默认返回 null —— 不支持地理编码的实现（如 FixedGeoLocator）走此降级，
     * 调用方据此回退到"未找到该城市的门店"。支持地理编码的实现（PositionStackGeoLocator）覆盖此方法。
     *
     * @param cityName 城市名（如"北京"）
     * @return 经纬度 + 城市名；未配置 key、调用失败或不支持时返回 null
     */
    default GeoLocation locateByCity(String cityName) {
        return null;
    }
}