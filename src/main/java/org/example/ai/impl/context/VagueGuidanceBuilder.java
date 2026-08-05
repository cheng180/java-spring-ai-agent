package org.example.ai.impl.context;

import org.example.ai.impl.location.HotCar;
import org.example.ai.impl.location.HotCarRepository;
import org.example.ai.impl.location.StoreInfo;
import org.example.ai.impl.location.StoreLocator;
import org.example.ai.impl.profile.CustomerProfile;
import org.example.ai.impl.profile.CustomerProfileRepository;
import org.example.ai.impl.routing.VagueAssessment;
import org.example.ai.knowledge.entity.SeriesKeys;
import org.example.ai.knowledge.hotness.AskCountTracker;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 程度引导文案生成器（#41 ticket，#35 规格决策 6/8）——按 {@link VagueAssessment}
 * 档位产出注入 UNRESTRICTED 上下文的引导段落，由 LLM 自然地向客户澄清。
 *
 * <p>档位 → 引导动作：</p>
 * <ul>
 *   <li>档 0 清晰 → 不生成引导（直接答）；</li>
 *   <li>档 1 浅模糊 → 候选 top 1-2 作为选项让用户确认；</li>
 *   <li>档 2 中模糊 → 有候选给 1-2 款匹配；无候选补问最缺的一个维度；</li>
 *   <li>档 3 深模糊 → <b>门店优先</b>：画像有城市 → 最近门店热销 top 1-2
 *       （门店无热度数据 → 全局热度兜底，同 BRAND 级排序源）；
 *       画像无城市 → 引导反问城市（话术带好处，<b>禁止静默退回全局</b>）。</li>
 * </ul>
 *
 * <p>画像即跨轮引导状态（决策 8）：城市已知不再问、偏好已知不重复问——
 * 画像读取发生在对话入口抓取入库之后，客户回答"杭州"类短消息当轮即升级为
 * 门店热销推荐；补问维度跳过画像历史偏好已覆盖的维度。
 * 一轮一问，不与其他问题堆叠。</p>
 *
 * <p>fail-safe（决策 12）：门店/热度/画像数据任何异常 → 退回可降级文案或 null
 * （组装器捕获后按"不注入引导"现状回退），绝不阻断对话主流程。</p>
 */
@Component
public class VagueGuidanceBuilder {

    private static final Logger log = LoggerFactory.getLogger(VagueGuidanceBuilder.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 画像渠道：#39 抓取当前仅覆盖 web 入口，macan 待稳定客户 ID 透传后接入 */
    private static final String PROFILE_CHANNEL = "web";

    /** 档 2 补问维度优先级：预算 > 车型 > 用途 > 能源（缺失时先问最收窄范围的） */
    private static final String[] MISSING_DIMENSION_ORDER = {
            "budget", "carType", "use", "energy"
    };

    private static final Map<String, String> DIMENSION_ASK_HINT = Map.of(
            "budget", "预算（如\"您预算大概多少？\"）",
            "carType", "车型偏好（如\"想看轿车还是SUV？\"）",
            "use", "主要用途（如\"主要通勤还是家用？\"）",
            "energy", "能源偏好（如\"倾向油车还是新能源？\"）"
    );

    private final StoreLocator storeLocator;
    private final HotCarRepository hotCarRepository;
    private final AskCountTracker askCountTracker;
    private final CustomerProfileRepository profileRepository;

    public VagueGuidanceBuilder(StoreLocator storeLocator, HotCarRepository hotCarRepository,
                                AskCountTracker askCountTracker,
                                CustomerProfileRepository profileRepository) {
        this.storeLocator = storeLocator;
        this.hotCarRepository = hotCarRepository;
        this.askCountTracker = askCountTracker;
        this.profileRepository = profileRepository;
    }

    /**
     * 按档位生成引导文案。
     *
     * @param assessment 模糊评定（null 或档 0 返回 null——不注入）
     * @param userId     对话入口用户 ID（读画像城市用；null 时按无画像处理）
     * @return 引导段落文本；null 表示不注入引导
     */
    public String build(VagueAssessment assessment, String userId) {
        if (assessment == null || assessment.tier() == VagueAssessment.TIER_CLEAR) {
            return null;
        }
        try {
            CustomerProfile profile = readProfile(userId);
            return switch (assessment.tier()) {
                case VagueAssessment.TIER_LIGHT -> lightGuidance(assessment);
                case VagueAssessment.TIER_MEDIUM -> mediumGuidance(assessment, profile);
                case VagueAssessment.TIER_DEEP -> deepGuidance(profile);
                default -> null;
            };
        } catch (Exception e) {
            log.warn("引导文案生成失败，降级为不注入引导（现状回退路径）: {}", e.getMessage());
            return null;
        }
    }

    // ---- 档 1：浅模糊 → 候选 top 1-2 选项确认 ----

    private String lightGuidance(VagueAssessment assessment) {
        List<String> options = rankByHeat(assessment.candidates()).stream()
                .limit(2).map(SeriesKeys::nameOf).toList();
        if (options.isEmpty()) return null;
        return "用户表述可能对应多个车系，候选：" + String.join("、", options) + "。"
                + "请只从中挑最可能的 1-2 个作为选项让用户确认（如\"您是想了解A还是B？\"），"
                + "不要展开介绍其他车系，不要同时问其他问题。";
    }

    /** 候选键按加权热度降序（#35：档 1 给热度 top 1-2）；热度查询失败保持原序（fail-safe） */
    private List<String> rankByHeat(List<String> seriesKeys) {
        try {
            return seriesKeys.stream()
                    .sorted(Comparator.comparingDouble(askCountTracker::getWeightedHeat).reversed())
                    .toList(); // 稳定排序：热度并列保持召回/别名原序
        } catch (Exception e) {
            log.warn("档 1 选项热度排序失败，保持原序: {}", e.getMessage());
            return seriesKeys;
        }
    }

    // ---- 档 2：中模糊 → 有候选给 1-2 款，无候选补问最缺维度 ----

    private String mediumGuidance(VagueAssessment assessment, CustomerProfile profile) {
        String signalsText = describeSignals(assessment.signals());
        if (!assessment.candidates().isEmpty()) {
            String names = assessment.candidates().stream()
                    .map(SeriesKeys::nameOf).collect(Collectors.joining("、"));
            return "用户已给出需求信号" + signalsText + "，但未锚定具体车系。"
                    + "资料中与之接近的候选：" + names + "。"
                    + "请从中推荐最合适的 1-2 款并各用一句话说明为什么适合，"
                    + "不要列更多选项，不要同时问其他问题。";
        }
        String missing = missingDimension(assessment.signals(), profileSignalKeys(profile));
        String hint = DIMENSION_ASK_HINT.getOrDefault(missing, "预算或用途");
        return "用户已给出需求信号" + signalsText + "，但信息不足以匹配具体车系。"
                + "请只补问最缺的一个维度——" + hint + "，一次只问这一个问题。";
    }

    // ---- 档 3：深模糊 → 门店优先引导（决策 6） ----

    private String deepGuidance(CustomerProfile profile) {
        String city = profile == null ? null : profile.city();
        if (city == null || city.isBlank()) {
            // 画像无城市 → 引导动作为反问城市，禁止静默退回全局
            return "用户没有给出明确购车需求，且不知道所在城市。"
                    + "本轮请先反问用户在哪个城市，并说明好处"
                    + "（如\"您在哪个城市？我帮您看看附近店哪款卖得最好～\"）。"
                    + "只问这一个问题，不要推荐具体车型。";
        }
        try {
            StoreInfo store = storeLocator.findByRegion(city);
            if (store != null) {
                List<HotCar> hot = hotCarRepository.getHotCars(store.id(), 2);
                if (!hot.isEmpty()) {
                    String names = hot.stream().map(HotCar::seriesName).collect(Collectors.joining("、"));
                    return "用户没有给出明确购车需求。已知城市：" + city
                            + "，最近门店「" + store.storeName() + "」近期热销：" + names + "。"
                            + "请以这 1-2 款热销车作为起点选项推荐，各一句话说明卖点，"
                            + "再用一个轻问题收尾（如用途或预算）。不要重复询问城市。";
                }
            }
            // 门店无热度数据 → 全局热度兜底（同 BRAND 级排序源）
            List<String> global = askCountTracker.topSeriesByHeat(2).stream()
                    .map(SeriesKeys::nameOf)
                    .toList();
            if (!global.isEmpty()) {
                return "用户没有给出明确购车需求。已知城市：" + city
                        + "，近期整体热门车系：" + String.join("、", global) + "。"
                        + "请以这 1-2 款作为起点选项推荐，各一句话说明卖点，"
                        + "再用一个轻问题收尾（如用途或预算）。不要重复询问城市。";
            }
        } catch (Exception e) {
            log.warn("门店/热度数据读取失败，深模糊引导降级（城市 {}）: {}", city, e.getMessage());
        }
        // 城市已知但门店/热度数据全缺：仍不重复问城市
        return "用户没有给出明确购车需求。已知城市：" + city + "，但暂无门店热销数据。"
                + "请用一个轻问题引导（如主要用途或预算），一步步缩小范围。"
                + "不要重复询问城市。";
    }

    /** 读画像——#39 抓取在对话入口先于本方法执行，当轮回答的城市/偏好已入库 */
    private CustomerProfile readProfile(String userId) {
        if (userId == null || userId.isBlank()) return null;
        try {
            return profileRepository.find(PROFILE_CHANNEL, userId).orElse(null);
        } catch (Exception e) {
            log.warn("画像读取失败，按无画像处理: {}", e.getMessage());
            return null;
        }
    }

    /** 画像偏好信号键集合（跨轮已知维度）；解析失败按空集降级 */
    private Set<String> profileSignalKeys(CustomerProfile profile) {
        if (profile == null || profile.preferenceSignals() == null
                || profile.preferenceSignals().isBlank()) return Set.of();
        try {
            Map<String, String> m = MAPPER.readValue(
                    profile.preferenceSignals(), new TypeReference<Map<String, String>>() {});
            return new LinkedHashSet<>(m.keySet());
        } catch (Exception e) {
            return Set.of();
        }
    }

    /** 信号描述："（budget=20万、use=通勤）"；空信号返回空串 */
    private static String describeSignals(Map<String, String> signals) {
        if (signals == null || signals.isEmpty()) return "";
        Map<String, String> zh = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : signals.entrySet()) {
            String key = switch (e.getKey()) {
                case "budget" -> "预算";
                case "use" -> "用途";
                case "carType" -> "车型";
                case "energy" -> "能源";
                default -> e.getKey();
            };
            zh.put(key, e.getValue());
        }
        return "（" + zh.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining("、")) + "）";
    }

    /** 缺失的最高优先级维度（本轮信号 + 画像历史偏好都算已知，决策 8 不重复问）；全齐时返回 budget 兜底 */
    private static String missingDimension(Map<String, String> signals, Set<String> profileKnown) {
        for (String dim : MISSING_DIMENSION_ORDER) {
            boolean knownNow = signals != null && signals.containsKey(dim);
            if (!knownNow && !profileKnown.contains(dim)) return dim;
        }
        return "budget";
    }
}