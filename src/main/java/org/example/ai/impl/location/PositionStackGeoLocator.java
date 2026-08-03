package org.example.ai.impl.location;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Function;

/**
 * positionstack 城市地理编码实现（#16 ticket，决策5）。
 *
 * 只做城市名 → 经纬度（forward geocoding），不做 IP 定位（positionstack 无 IP 能力，
 * IP 路径仍由 FixedGeoLocator 负责）。用作 getStoreInfo(city) 在 region LIKE 未命中时的兜底。
 *
 * 降级策略：未配置 key、HTTP 失败、返回空 data 或解析失败时一律返回 null，
 * 调用方据此回退到"未找到该城市的门店"。
 *
 * API key 走环境变量 POSITIONSTACK_API_KEY，不进仓库（仓库 public）。
 */
@Component
public class PositionStackGeoLocator implements GeoLocator {

    private static final Logger log = LoggerFactory.getLogger(PositionStackGeoLocator.class);
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String apiKey;
    private final String baseUrl;
    private final Function<String, String> httpGet;

    @Autowired
    public PositionStackGeoLocator(
            @Value("${positionstack.api-key:}") String apiKey,
            @Value("${positionstack.base-url:http://api.positionstack.com/v1/forward}") String baseUrl) {
        this(apiKey, baseUrl, PositionStackGeoLocator::jdkHttpGet);
    }

    /** 测试构造器：注入假的 httpGet，避免真实网络调用。 */
    public PositionStackGeoLocator(String apiKey, String baseUrl, Function<String, String> httpGet) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.httpGet = httpGet;
    }

    /**
     * IP 定位不在本实现职责内（positionstack 无 IP 能力）。
     */
    @Override
    public GeoLocation locate(String userIp) {
        throw new UnsupportedOperationException(
                "PositionStackGeoLocator 仅做城市地理编码，IP 定位请用 FixedGeoLocator");
    }

    @Override
    public GeoLocation locateByCity(String cityName) {
        if (cityName == null || cityName.isBlank()) return null;
        if (apiKey == null || apiKey.isBlank()) {
            log.debug("positionstack api-key 未配置，locateByCity('{}') 降级返回 null", cityName);
            return null;
        }
        try {
            String query = normalizeQuery(cityName);
            String url = baseUrl
                    + "?access_key=" + apiKey
                    + "&query=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
                    + "&limit=1";
            String body = httpGet.apply(url);
            return parse(cityName, body);
        } catch (Exception e) {
            log.warn("positionstack 地理编码失败 city={}: {}", cityName, e.getMessage());
            return null;
        }
    }

    /**
     * positionstack 要求 query 至少 3 个字符，而中文城市名常为 2 字（北京/上海/广州/深圳…），
     * 直接提交会触发 validation_error。此处对不足 3 字且无行政后缀的城市名补"市"，
     * 凑足字符数并消除歧义（"北京"→"北京市"）。已有后缀或本身≥3 字的保持原样。
     */
    static String normalizeQuery(String city) {
        String c = city.trim();
        boolean hasSuffix = c.endsWith("市") || c.endsWith("省") || c.endsWith("区") || c.endsWith("县");
        if (c.length() < 3 && !hasSuffix) {
            c = c + "市";
        }
        return c;
    }

    // ---- 内部 ----

    private GeoLocation parse(String cityName, String body) {
        try {
            JsonNode root = MAPPER.readTree(body);
            // positionstack 出错（如 rate_limit_reached / validation_error）时返回 error 对象而非 data
            JsonNode error = root.get("error");
            if (error != null) {
                log.warn("positionstack 返回错误 city={}: {}", cityName, error.path("code").asText());
                return null;
            }
            JsonNode data = root.get("data");
            if (data == null || !data.isArray() || data.isEmpty()) return null;
            JsonNode first = data.get(0);
            double lat = first.path("latitude").asDouble(Double.NaN);
            double lng = first.path("longitude").asDouble(Double.NaN);
            if (Double.isNaN(lat) || Double.isNaN(lng)) return null;
            return new GeoLocation(lat, lng, cityName);
        } catch (Exception e) {
            log.warn("positionstack 响应解析失败: {}", e.getMessage());
            return null;
        }
    }

    private static String jdkHttpGet(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            return resp.body();
        } catch (Exception e) {
            throw new IllegalStateException("positionstack HTTP 请求失败: " + e.getMessage(), e);
        }
    }
}