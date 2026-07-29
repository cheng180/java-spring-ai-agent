package org.example.ai.location;

/**
 * 根据用户 IP 定位经纬度（#10 ticket，决策6）。
 *
 * 接口与实现分离：首版假数据返回固定坐标，后期换高德 API 只替换实现类。
 */
public interface GeoLocator {

    /**
     * 根据用户 IP 返回地理位置。
     *
     * @param userIp 用户 IP 地址（首版假数据不实际使用此参数）
     * @return 经纬度 + 城市名，解析失败返回 null
     */
    GeoLocation locate(String userIp);
}