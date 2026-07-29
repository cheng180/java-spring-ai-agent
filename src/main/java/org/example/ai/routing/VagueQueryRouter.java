package org.example.ai.routing;

import org.example.ai.knowledge.entity.EntityResolver;
import org.example.ai.knowledge.entity.ResolvedEntity;
import org.example.ai.config.DynamicKeywordBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 模糊语义路由器 —— 三层匹配策略（#13 ticket，决策9/10/11）。
 *
 * 在 CarSalesAgent.chat() 中同步调用，位于 ChatClient 链之前。
 * L1（精确匹配）本次实现；L2/L3 留桩后续 ticket 补。
 */
@Component
public class VagueQueryRouter {

    private static final Logger log = LoggerFactory.getLogger(VagueQueryRouter.class);

    /** L1 父块相似度阈值：EntityResolver 命中后需通过此验证才算 EXACT */
    private static final double L1_CONFIDENCE = 0.7;

    private final EntityResolver entityResolver;
    private final DynamicKeywordBuilder keywordBuilder;
    private final VectorStore vectorStore;

    public VagueQueryRouter(EntityResolver entityResolver, DynamicKeywordBuilder keywordBuilder,
                            VectorStore vectorStore) {
        this.entityResolver = entityResolver;
        this.keywordBuilder = keywordBuilder;
        this.vectorStore = vectorStore;
    }

    /**
     * 路由入口。根据用户消息和对话历史决定匹配类型。
     *
     * @param userMessage 用户当前消息
     * @param historyText 最近 N 轮对话文本（L3 使用，L1/L2 忽略）
     * @return MatchResult 或 null（无匹配，走现有检索回退路径）
     */
    public MatchResult route(String userMessage, String historyText) {
        if (userMessage == null || userMessage.isBlank()) return null;

        // L1：精确车系匹配
        MatchResult l1 = tryExactMatch(userMessage);
        if (l1 != null) return l1;

        // L2：品牌级匹配（留桩，后续 ticket 实现）
        // MatchResult l2 = tryBrandMatch(userMessage);
        // if (l2 != null) return l2;

        // L3：级联兜底（留桩，后续 ticket 实现）
        // MatchResult l3 = tryVagueMatch(userMessage, historyText);
        // if (l3 != null) return l3;

        return null;
    }

    /**
     * L1：精确车系匹配。
     * EntityResolver 命中 → 取 top-1 → 父块相似度验证（阈值 0.7）。
     */
    MatchResult tryExactMatch(String userMessage) {
        List<ResolvedEntity> entities = entityResolver.resolve(userMessage);
        if (entities.isEmpty()) {
            log.debug("L1: EntityResolver 无命中");
            return null;
        }

        ResolvedEntity top = entities.get(0);
        String seriesKey = top.seriesKey(); // "比亚迪-宋PLUS DM-i"

        // 父块相似度验证
        Filter.Expression filter = new FilterExpressionBuilder().and(
                new FilterExpressionBuilder().eq("type", "车源"),
                new FilterExpressionBuilder().and(
                        new FilterExpressionBuilder().eq("level", "parent"),
                        new FilterExpressionBuilder().eq("series_id", seriesKey))
        ).build();

        List<Document> parentDocs = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(userMessage)
                        .topK(1)
                        .similarityThreshold(L1_CONFIDENCE)
                        .filterExpression(filter)
                        .build());

        if (parentDocs.isEmpty()) {
            log.debug("L1: EntityResolver 命中 {}，但父块相似度不足 0.7", seriesKey);
            return null;
        }

        log.info("L1 EXACT: {} (entityId={})", seriesKey, top.entityId());
        return new MatchResult(
                MatchResult.MatchType.EXACT,
                seriesKey,
                null,
                null,
                false,
                false,
                null
        );
    }
}