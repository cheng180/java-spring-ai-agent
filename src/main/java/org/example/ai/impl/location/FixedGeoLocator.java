package org.example.ai.impl.location;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * GeoLocator 假实现：固定返回杭州未来科技城坐标（#10 ticket，决策6）。
 *
 * 后期替换为高德 API 实现类，GeoLocator 接口不变。
 */
@Component
public class FixedGeoLocator implements GeoLocator {

    private static final Logger log = LoggerFactory.getLogger(FixedGeoLocator.class);

    /** 杭州未来科技城 */
    private static final GeoLocation FIXED = new GeoLocation(30.28, 120.02, "杭州");

    @Override
    public GeoLocation locate(String userIp) {
        log.debug("FixedGeoLocator: 返回固定坐标 ({}), userIp={}", FIXED, userIp);
        return FIXED;
    }
}