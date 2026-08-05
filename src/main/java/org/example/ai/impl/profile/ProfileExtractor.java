package org.example.ai.impl.profile;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 客户线索抽取器（#39 ticket）——纯代码抓取，不依赖 LLM。
 *
 * <p>电话：11 位手机号正则（前后不得贴数字，防订单号/座机误抓）。
 * 城市：常见城市词表（硬编码，小规模稳定集，同 {@code IdleChatGate} 词表风格）
 * + store_config.region 动态词（懒加载——建表在 CommandLineRunner，构造时表可能还不存在）。</p>
 */
@Component
public class ProfileExtractor {

    /** 11 位手机号：1[3-9] 开头，前后不得紧邻其他数字 */
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)(1[3-9]\\d{9})(?!\\d)");

    /** 常见城市词表（覆盖门店城市 + 主流车市城市） */
    private static final Set<String> COMMON_CITIES = Set.of(
            "北京", "上海", "广州", "深圳", "成都", "杭州", "重庆", "武汉", "西安", "南京",
            "苏州", "天津", "郑州", "长沙", "青岛", "大连", "宁波", "厦门", "福州", "济南",
            "合肥", "沈阳", "哈尔滨", "长春", "石家庄", "太原", "昆明", "贵阳", "南宁", "兰州",
            "无锡", "东莞", "佛山", "珠海", "温州", "海口", "三亚", "南昌", "乌鲁木齐", "呼和浩特"
    );

    private final JdbcTemplate jdbc;

    /** 懒加载的 region 词表（store_config 建表晚于本 bean 构造） */
    private volatile List<String> regionVocab;

    public ProfileExtractor(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 抽取句中第一个 11 位手机号 */
    public Optional<String> extractPhone(String message) {
        if (message == null || message.isBlank()) return Optional.empty();
        Matcher m = PHONE.matcher(message);
        if (m.find()) return Optional.of(m.group(1));
        return Optional.empty();
    }

    /** 抽取城市：常见城市词表优先，其次 store_config.region 动态词 */
    public Optional<String> extractCity(String message) {
        if (message == null || message.isBlank()) return Optional.empty();
        for (String city : COMMON_CITIES) {
            if (message.contains(city)) return Optional.of(city);
        }
        for (String region : regions()) {
            if (message.contains(region)) return Optional.of(region);
            // 去行政后缀的词干也匹配（"浦东" → "浦东新区"）
            String stem = stripSuffix(region);
            if (!stem.equals(region) && stem.length() >= 2 && message.contains(stem)) {
                return Optional.of(region);
            }
        }
        return Optional.empty();
    }

    private List<String> regions() {
        List<String> vocab = regionVocab;
        if (vocab == null) {
            vocab = loadRegions();
            regionVocab = vocab;
        }
        return vocab;
    }

    private List<String> loadRegions() {
        try {
            List<String> rows = jdbc.queryForList(
                    "SELECT DISTINCT region FROM store_config WHERE is_active = 1 AND region IS NOT NULL",
                    String.class);
            List<String> result = new ArrayList<>();
            for (String r : rows) {
                if (r != null && !r.isBlank()) result.add(r.trim());
            }
            return result;
        } catch (Exception e) {
            return List.of(); // 表不可用时静默降级为纯城市词表
        }
    }

    private static String stripSuffix(String region) {
        return region.replaceAll("(新区|区|县|市)$", "");
    }
}