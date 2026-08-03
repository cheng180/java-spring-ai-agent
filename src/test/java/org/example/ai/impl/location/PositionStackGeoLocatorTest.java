package org.example.ai.impl.location;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PositionStackGeoLocator 单元测试（#16-T1）。
 * 通过测试构造器注入假的 httpGet，避免真实网络调用。
 */
class PositionStackGeoLocatorTest {

    private static final String BASE = "http://api.positionstack.test/v1/forward";

    private PositionStackGeoLocator locator(String apiKey, String responseBody) {
        return new PositionStackGeoLocator(apiKey, BASE, url -> responseBody);
    }

    @Test
    @DisplayName("正常响应：解析出经纬度 + 城市名")
    void parsesCoordsFromPositionstackResponse() {
        String json = """
                {"data":[{"latitude":39.9042,"longitude":116.4074,"name":"Beijing","country":"China"}]}
                """;
        GeoLocation loc = locator("test-key", json).locateByCity("北京");

        assertThat(loc).isNotNull();
        assertThat(loc.lat()).isEqualTo(39.9042);
        assertThat(loc.lng()).isEqualTo(116.4074);
        assertThat(loc.city()).isEqualTo("北京");
    }

    @Test
    @DisplayName("未配置 api-key：降级返回 null，不抛异常")
    void returnsNullWhenApiKeyBlank() {
        GeoLocation loc = locator("", "{\"data\":[{\"latitude\":1,\"longitude\":2}]}").locateByCity("北京");
        assertThat(loc).isNull();
    }

    @Test
    @DisplayName("data 为空数组：返回 null")
    void returnsNullWhenDataEmpty() {
        GeoLocation loc = locator("test-key", "{\"data\":[]}").locateByCity("不存在的城市");
        assertThat(loc).isNull();
    }

    @Test
    @DisplayName("无 data 字段（错误响应）：返回 null")
    void returnsNullWhenNoDataField() {
        GeoLocation loc = locator("test-key", "{\"error\":{\"code\":\"401\"}}").locateByCity("北京");
        assertThat(loc).isNull();
    }

    @Test
    @DisplayName("HTTP 抛异常：捕获并返回 null")
    void returnsNullWhenHttpFails() {
        PositionStackGeoLocator loc = new PositionStackGeoLocator(
                "test-key", BASE, url -> { throw new IllegalStateException("network down"); });
        assertThat(loc.locateByCity("北京")).isNull();
    }

    @Test
    @DisplayName("非法 JSON：返回 null")
    void returnsNullWhenMalformedJson() {
        GeoLocation loc = locator("test-key", "not-a-json").locateByCity("北京");
        assertThat(loc).isNull();
    }

    @Test
    @DisplayName("latitude/longitude 缺失：返回 null")
    void returnsNullWhenCoordsMissing() {
        GeoLocation loc = locator("test-key", "{\"data\":[{\"name\":\"Beijing\"}]}").locateByCity("北京");
        assertThat(loc).isNull();
    }

    @Test
    @DisplayName("cityName 为 null/空白：返回 null")
    void returnsNullWhenCityBlank() {
        assertThat(locator("test-key", "{}").locateByCity(null)).isNull();
        assertThat(locator("test-key", "{}").locateByCity("  ")).isNull();
    }

    @Test
    @DisplayName("locate(IP) 抛 UnsupportedOperationException（IP 定位归 FixedGeoLocator）")
    void locateThrowsUnsupported() {
        assertThatThrownBy(() -> locator("test-key", "{}").locate("1.2.3.4"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // ---- normalizeQuery（positionstack 要求 query ≥3 字符）----

    @Test
    @DisplayName("normalizeQuery：2 字中文城市补'市'（北京→北京市）")
    void normalizeQueryAppendsShiForShortCity() {
        assertThat(PositionStackGeoLocator.normalizeQuery("北京")).isEqualTo("北京市");
        assertThat(PositionStackGeoLocator.normalizeQuery("上海")).isEqualTo("上海市");
        assertThat(PositionStackGeoLocator.normalizeQuery("广州")).isEqualTo("广州市");
    }

    @Test
    @DisplayName("normalizeQuery：已有行政后缀或≥3 字的保持原样")
    void normalizeQueryLeavesSuffixedOrLongCity() {
        assertThat(PositionStackGeoLocator.normalizeQuery("北京市")).isEqualTo("北京市");
        assertThat(PositionStackGeoLocator.normalizeQuery("哈尔滨")).isEqualTo("哈尔滨");
        assertThat(PositionStackGeoLocator.normalizeQuery("石家庄")).isEqualTo("石家庄");
        assertThat(PositionStackGeoLocator.normalizeQuery("朝阳区")).isEqualTo("朝阳区");
    }
}