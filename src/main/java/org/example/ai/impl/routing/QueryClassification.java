package org.example.ai.impl.routing;

import java.util.List;

/**
 * 查询粒度分类结果（#24 ticket）。
 *
 * @param level      查询级别
 * @param brand      命中的品牌名（BRAND/FAMILY/SERIES 时有值）
 * @param seriesKeys 相关车系 key 列表：BRAND=品牌全部在售车系，
 *                   FAMILY=命中的车系，SERIES=单车系；UNRESTRICTED 为空
 */
public record QueryClassification(QueryLevel level, String brand, List<String> seriesKeys) {

    public static QueryClassification unrestricted() {
        return new QueryClassification(QueryLevel.UNRESTRICTED, null, List.of());
    }
}
