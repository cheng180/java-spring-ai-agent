package org.example.ai.impl.routing;

import org.example.ai.config.DynamicKeywordBuilder;
import org.example.ai.knowledge.entity.ResolvedEntity;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 查询粒度分类器（#24 ticket，#21 spec）—— 零基础设施依赖的纯逻辑。
 *
 * <p>按判定序（先命中先返回）把用户消息归入 {@link QueryLevel}：</p>
 * <ol>
 *   <li>含细节触发词 → UNRESTRICTED（细节问题维持全量数据，防幻觉手段不动）</li>
 *   <li>实体命中：1 个 → SERIES；同品牌 ≥2 个 → 数量==品牌全部在售车系数或 &gt;3 → BRAND
 *       （裸品牌名会被 EntityResolver Layer-2 命中该品牌全部车系），否则 FAMILY；
 *       跨品牌 ≥2 个 → UNRESTRICTED（对比查询需要两边完整数据）</li>
 *   <li>无实体但品牌关键词命中：≥2 车系 → BRAND；1 车系 → SERIES（覆盖单系列品牌）</li>
 *   <li>其他 → UNRESTRICTED（现状主路径/回退路径）</li>
 * </ol>
 */
@Component
public class QueryLevelClassifier {

    /** 细节触发词：命中即全量展开（"怎么样"不在其中——走车系级摘要） */
    private static final Set<String> DETAIL_WORDS = Set.of(
            // 价格类
            "多少钱", "价格", "优惠", "落地价",
            // 预算类（#31：客户开口提预算就是在谈价格，需全量数据接匹配话题）
            "万", "预算", "以内", "左右",
            // 参数类
            "配置", "参数", "续航", "油耗",
            // 细节类
            "详细", "具体",
            // 对比类
            "区别", "对比", "哪个好", "哪款好", "还是"
    );

    /** 同品牌实体数超过该值直接判 BRAND（裸品牌名命中大量车系的保险丝） */
    private static final int BRAND_ENTITY_THRESHOLD = 3;

    private final DynamicKeywordBuilder keywordBuilder;

    public QueryLevelClassifier(DynamicKeywordBuilder keywordBuilder) {
        this.keywordBuilder = keywordBuilder;
    }

    public QueryClassification classify(String userMessage, List<ResolvedEntity> matchedSeries) {
        if (userMessage == null || userMessage.isBlank()) {
            return QueryClassification.unrestricted();
        }

        // 1. 细节词优先——细节问题需要完整数据，任何级别都不拦截
        String lower = userMessage.toLowerCase();
        for (String word : DETAIL_WORDS) {
            if (lower.contains(word)) return QueryClassification.unrestricted();
        }

        // 2. 实体命中分支
        if (matchedSeries != null && !matchedSeries.isEmpty()) {
            if (matchedSeries.size() == 1) {
                ResolvedEntity only = matchedSeries.get(0);
                return new QueryClassification(QueryLevel.SERIES, only.brand(),
                        List.of(only.seriesKey()));
            }

            Set<String> brands = matchedSeries.stream()
                    .map(ResolvedEntity::brand).collect(Collectors.toCollection(LinkedHashSet::new));
            if (brands.size() >= 2) {
                // 跨品牌（对比/并列提问）→ 需要两边完整数据
                return QueryClassification.unrestricted();
            }

            String brand = brands.iterator().next();
            int brandTotal = uniqueSeriesCount(keywordBuilder.getSeriesKeys(brand));
            boolean looksLikeBareBrand = matchedSeries.size() > BRAND_ENTITY_THRESHOLD
                    || (brandTotal > 0 && matchedSeries.size() >= brandTotal);
            if (looksLikeBareBrand) {
                return new QueryClassification(QueryLevel.BRAND, brand,
                        List.copyOf(keywordBuilder.getSeriesKeys(brand)));
            }
            return new QueryClassification(QueryLevel.FAMILY, brand,
                    matchedSeries.stream().map(ResolvedEntity::seriesKey).toList());
        }

        // 3. 无实体：品牌/车系关键词兜底
        for (String keyword : keywordBuilder.extractKeywords(userMessage)) {
            Set<String> keys = new LinkedHashSet<>(keywordBuilder.getSeriesKeys(keyword));
            if (keys.size() >= 2) {
                String brand = extractBrand(keys.iterator().next());
                return new QueryClassification(QueryLevel.BRAND, brand, List.copyOf(keys));
            }
            if (keys.size() == 1) {
                String seriesKey = keys.iterator().next();
                return new QueryClassification(QueryLevel.SERIES, extractBrand(seriesKey),
                        List.of(seriesKey));
            }
        }

        // 4. 无信号 → 现状回退路径
        return QueryClassification.unrestricted();
    }

    private static int uniqueSeriesCount(List<String> seriesKeys) {
        return new LinkedHashSet<>(seriesKeys).size();
    }

    /** 从 "比亚迪-宋PLUS DM-i" 提取品牌名 "比亚迪" */
    private static String extractBrand(String seriesKey) {
        if (seriesKey == null) return null;
        int idx = seriesKey.indexOf('-');
        return idx > 0 ? seriesKey.substring(0, idx) : seriesKey;
    }
}