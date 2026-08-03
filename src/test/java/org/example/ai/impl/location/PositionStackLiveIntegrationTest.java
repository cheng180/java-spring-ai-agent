package org.example.ai.impl.location;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * positionstack 实联调集成测试（#16-T4）。
 *
 * 只在设置了 POSITIONSTACK_API_KEY 环境变量时运行（走生产构造器 + 真实 HTTP），
 * 未设置时自动跳过——保证 CI / 无 key 环境不受影响。
 *
 * 只发 1 个真实请求：免费档限速 2 req/s，多个连发会触发 rate_limit_reached；
 * "无结果→null""错误响应→null"等分支已由 PositionStackGeoLocatorTest 用假 httpGet 覆盖。
 *
 * 本地运行示例：
 *   POSITIONSTACK_API_KEY=&lt;真实key&gt; ./mvnw test -Dtest=PositionStackLiveIntegrationTest
 */
@EnabledIfEnvironmentVariable(named = "POSITIONSTACK_API_KEY", matches = ".+")
class PositionStackLiveIntegrationTest {

    @Test
    @DisplayName("真实 key：北京 → 补'市'为北京市 → 合理经纬度（约 39.9, 116.4）")
    void liveGeocodeBeijing() {
        String key = System.getenv("POSITIONSTACK_API_KEY");
        // 生产构造器：内部用 JDK HttpClient 真实请求 positionstack（免费档走 http）
        PositionStackGeoLocator locator =
                new PositionStackGeoLocator(key, "http://api.positionstack.com/v1/forward");

        GeoLocation loc = locator.locateByCity("北京");

        assertThat(loc).isNotNull();
        assertThat(loc.lat()).isBetween(39.0, 41.0);
        assertThat(loc.lng()).isBetween(115.0, 118.0);
        assertThat(loc.city()).isEqualTo("北京");
    }
}