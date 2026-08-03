package org.example.ai.impl.routing;

/**
 * 查询粒度级别（#24 ticket，#21 spec）。
 *
 * <p>分层检索按此级别决定注入上下文的详细程度：</p>
 * <ul>
 *   <li>{@link #BRAND} — 品牌级：只给车系数量 + top1 推荐</li>
 *   <li>{@link #FAMILY} — 车系族级：只给命中车系的摘要（截断车型清单）</li>
 *   <li>{@link #SERIES} — 车系级：给该车系完整父块，问是否深入了解</li>
 *   <li>{@link #UNRESTRICTED} — 未受限：维持现状（细节追问全量展开 / 无匹配回退泛检索）</li>
 * </ul>
 */
public enum QueryLevel {
    BRAND, FAMILY, SERIES, UNRESTRICTED
}
