package org.example.ai.agent;

import org.example.ai.agent.prompt.PromptTemplates;
import org.example.ai.agent.tool.CarSalesTools;
import org.example.ai.config.DynamicKeywordBuilder;
import org.example.ai.knowledge.entity.EntityResolver;
import org.example.ai.knowledge.entity.ResolvedEntity;
import org.example.ai.knowledge.hotness.AskCountTracker;
import org.example.ai.location.GeoLocation;
import org.example.ai.location.GeoLocator;
import org.example.ai.location.StoreLocator;
import org.example.ai.routing.MatchResult;
import org.example.ai.routing.VagueQueryRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import reactor.core.publisher.Flux;

/**
 * 汽车销售 Agent —— LLM 自主决策，代码只提供工具。
 *
 * 检索（#4 ticket）：两阶段 ——
 * 1. 相似度召回：子块 + 百科/话术，排除父块（避免父块挤占 topK）
 * 2. 父块确定性展开：命中车系的父块（热度/价格区间/车型列表）必达，
 *    EntityResolver 直接命中的车系也无条件附加父块
 */
@Component
public class CarSalesAgent {

    private static final Logger log = LoggerFactory.getLogger(CarSalesAgent.class);

    private static final int MEMORY_MAX_MESSAGES = 40;
    private static final int IDLE_CHAT_LIMIT = 3;
    private static final String IDLE_TERMINATION = "买车的事随时找我，先不打扰您了～有需要再聊！";
    private static final int RAG_TOPK = 5;
    private static final double RAG_THRESHOLD = 0.5;
    /** 父块匹配置信度门槛：top-1 低于此值视为"非车系问题"，走泛检索 */
    private static final double SERIES_CONFIDENCE = 0.6;

    private final Map<String, Integer> idleCounters = new ConcurrentHashMap<>();

    private final ChatClient chatClient;
    private final ChatMemory chatMemory;
    private final AskCountTracker askCountTracker;
    private final EntityResolver entityResolver;
    private final DynamicKeywordBuilder keywordBuilder;
    private final VagueQueryRouter router;
    private final GeoLocator geoLocator;
    private final StoreLocator storeLocator;
    private final VectorStore vectorStore;

    /** 通用汽车话题词（不含品牌/车系名——那一侧由 DynamicKeywordBuilder + EntityResolver 覆盖，#12 ticket） */
    private static final Set<String> CAR_TOPIC_WORDS = Set.of(
            "买车", "购车", "看车", "试驾", "订车", "提车",
            "多少钱", "报价", "价格", "售价", "指导价", "落地价", "优惠",
            "库存", "现车", "有货", "多久提车",
            "suv", "mpv", "轿车", "新能源", "纯电", "混动", "油车", "电车", "增程",
            "耗油", "续航", "配置", "性能", "空间",
            "门店", "地址", "电话", "在哪里", "在哪", "怎么去",
            "到店", "预约", "试驾车", "转人工", "销售",
            "推荐", "预算", "家用", "代步", "通勤", "性价比"
    );

    public CarSalesAgent(ChatModel chatModel, CarSalesTools tools, VectorStore vectorStore,
                         @Value("${company.name}") String companyName,
                         AskCountTracker askCountTracker,
                         EntityResolver entityResolver,
                         DynamicKeywordBuilder keywordBuilder,
                         VagueQueryRouter router,
                         GeoLocator geoLocator,
                         StoreLocator storeLocator) {
        this.askCountTracker = askCountTracker;
        this.entityResolver = entityResolver;
        this.keywordBuilder = keywordBuilder;
        this.router = router;
        this.geoLocator = geoLocator;
        this.storeLocator = storeLocator;
        this.vectorStore = vectorStore;

        ChatMemoryRepository repo = new InMemoryChatMemoryRepository();
        this.chatMemory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(repo)
                .maxMessages(MEMORY_MAX_MESSAGES)
                .build();

        MessageChatMemoryAdvisor memoryAdvisor = MessageChatMemoryAdvisor.builder(chatMemory).build();

        String systemPrompt = PromptTemplates.systemPrompt(companyName);

        // 不再使用 QuestionAnswerAdvisor —— 检索改为两阶段手动调用（#4 ticket）
        this.chatClient = ChatClient.builder(chatModel)
                .defaultSystem(systemPrompt)
                .defaultAdvisors(memoryAdvisor)
                .defaultTools(tools)
                .build();

        log.info("CarSalesAgent 初始化完成（公司：{}, 设施：两阶段检索 topK={}/threshold={}, EntityResolver：{} 别名）",
                companyName, RAG_TOPK, RAG_THRESHOLD, entityResolver.aliasCount());
    }

    /**
     * 处理用户消息。
     *
     * @param userId      用户标识（会话隔离）
     * @param userMessage 用户消息文本
     * @param userIp      客户端 IP（GeoLocator 定位用，FixedGeoLocator 当前忽略此参数）
     */
    public String chat(String userId, String userMessage, String userIp) {
        List<ResolvedEntity> matchedSeries = resolveEntities(userMessage);

        int idleCount = idleCounters.getOrDefault(userId, 0);
        boolean isIdle = isIdleChat(userMessage) && matchedSeries.isEmpty();
        boolean terminated = false;
        String response;

        if (isIdle) {
            idleCount = idleCounters.merge(userId, 1, Integer::sum);
            if (idleCount > IDLE_CHAT_LIMIT) {
                response = IDLE_TERMINATION;
                terminated = true;
            } else {
                response = chatClient.prompt()
                        .user(userMessage)
                        .advisors(a -> a.param("chat_memory_conversation_id", userId))
                        .call()
                        .content();
            }
        } else {
            idleCounters.remove(userId);
            idleCount = 0;
            for (ResolvedEntity e : matchedSeries) {
                askCountTracker.recordMention(e.seriesKey());
            }

            // VagueQueryRouter 路由（#13 ticket）
            String historyText = buildHistoryText(userId);
            MatchResult route = router.route(userMessage, historyText);

            if (route != null && route.needsConfirm()) {
                // BRAND 或 VAGUE+确认 → 直接返回追问文本，不调 LLM
                response = route.followUpText();
            } else {
                // EXACT / null → 走两阶段检索（#4 ticket）
                String ragContext = retrieveContext(userMessage, matchedSeries);

                // VAGUE + needsInference → 追问文本注入 system prompt
                if (route != null && route.needsInference() && route.followUpText() != null) {
                    ragContext = ragContext + "\n## 引导提示\n" + route.followUpText();
                }

                response = chatClient.prompt()
                        .user(userMessage)
                        .system(ragContext)
                        .advisors(a -> a.param("chat_memory_conversation_id", userId))
                        .call()
                        .content();
            }
        }

        logConversation(userId, userMessage, response, idleCount, isIdle, terminated, matchedSeries);
        return response;
    }

    /**
     * 三层检索（#4 ticket，2026-07-29）：
     *
     * 主路径（命中车系）：
     *   阶段一 — 父块相似度召回（车系级语义匹配，阈值 0.6，topK=3）
     *   阶段二 — 命中车系子块全量展开（topK=20）
     *   不注入百科/话术（有车系锚点时通用知识是噪音）
     *
     * 回退路径（未命中车系）：
     *   子块泛检索（topK=5）+ 百科/话术补充（topK=3）
     */
    private String retrieveContext(String userMessage, List<ResolvedEntity> matchedSeries) {
        // EntityResolver 命中的车系（别名匹配）
        Set<String> entitySeries = matchedSeries.stream()
                .map(ResolvedEntity::seriesKey).collect(Collectors.toSet());

        // ---- 阶段一：父块相似度召回，阈值提至 0.6（决策：非车系问题不硬套） ----
        Filter.Expression parentOnly = new FilterExpressionBuilder().and(
                new FilterExpressionBuilder().eq("type", "车源"),
                new FilterExpressionBuilder().eq("level", "parent")
        ).build();

        List<Document> parentDocs = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(userMessage)
                        .topK(3)
                        .similarityThreshold(SERIES_CONFIDENCE)
                        .filterExpression(parentOnly)
                        .build());

        Set<String> hitSeries = new LinkedHashSet<>();
        for (Document p : parentDocs) {
            String sid = metaStr(p, "series_id");
            if (sid != null && !sid.isEmpty()) hitSeries.add(sid);
        }
        hitSeries.addAll(entitySeries);

        // ---- 回退路径：父块相似度 + EntityResolver 都没命中任何车系 ----
        if (hitSeries.isEmpty()) {
            Filter.Expression childOnly = new FilterExpressionBuilder().and(
                    new FilterExpressionBuilder().eq("type", "车源"),
                    new FilterExpressionBuilder().eq("level", "child")
            ).build();
            List<Document> childDocs = vectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query(userMessage)
                            .topK(RAG_TOPK)
                            .similarityThreshold(RAG_THRESHOLD)
                            .filterExpression(childOnly)
                            .build());

            Filter.Expression nonCar = new FilterExpressionBuilder().ne("type", "车源").build();
            List<Document> knowledgeDocs = vectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query(userMessage)
                            .topK(3)
                            .similarityThreshold(RAG_THRESHOLD)
                            .filterExpression(nonCar)
                            .build());

            return buildContext(Collections.emptyList(), childDocs, knowledgeDocs);
        }

        // ---- 主路径：命中车系 ----

        // 补充 EntityResolver 命中但父块相似度没搜到的父块
        for (String sid : entitySeries) {
            boolean alreadyIn = parentDocs.stream()
                    .anyMatch(d -> sid.equals(metaStr(d, "series_id")));
            if (!alreadyIn) {
                Filter.Expression pf = new FilterExpressionBuilder().and(
                        new FilterExpressionBuilder().eq("type", "车源"),
                        new FilterExpressionBuilder().and(
                                new FilterExpressionBuilder().eq("level", "parent"),
                                new FilterExpressionBuilder().eq("series_id", sid))
                ).build();
                parentDocs.addAll(vectorStore.similaritySearch(
                        SearchRequest.builder().query(sid).topK(1).filterExpression(pf).build()));
            }
        }

        // ---- 阶段二：命中车系子块全量展开（决策：全量，不截断） ----
        List<Document> childDocs = new ArrayList<>();
        Set<Object> seenSkuIds = new HashSet<>();
        for (String sid : hitSeries) {
            Filter.Expression cf = new FilterExpressionBuilder().and(
                    new FilterExpressionBuilder().eq("type", "车源"),
                    new FilterExpressionBuilder().and(
                            new FilterExpressionBuilder().eq("level", "child"),
                            new FilterExpressionBuilder().eq("parent_series_id", sid))
            ).build();
            for (Document c : vectorStore.similaritySearch(
                    SearchRequest.builder().query(sid).topK(20).filterExpression(cf).build())) {
                Object skuId = c.getMetadata().get("sku_id");
                if (skuId != null && seenSkuIds.add(skuId)) childDocs.add(c);
            }
        }

        // 有车系锚点时跳过百科/话术（决策：避免通用知识与具体车系混淆）
        return buildContext(parentDocs, childDocs, Collections.emptyList());
    }

    /** 组装上下文 */
    private String buildContext(List<Document> parentDocs, List<Document> childDocs,
                                 List<Document> knowledgeDocs) {
        StringBuilder ctx = new StringBuilder();
        if (!parentDocs.isEmpty()) {
            ctx.append("## 匹配车系\n");
            for (Document d : parentDocs) ctx.append(d.getText()).append("\n");
        }
        if (!childDocs.isEmpty()) {
            ctx.append("\n## 在售车型\n");
            for (Document d : childDocs) ctx.append("- ").append(d.getText()).append("\n");
        }
        if (!knowledgeDocs.isEmpty()) {
            ctx.append("\n## 相关知识\n");
            for (Document d : knowledgeDocs) {
                ctx.append("- [").append(metaStr(d, "type") != null ? metaStr(d, "type") : "?")
                   .append("] ").append(d.getText()).append("\n");
            }
        }
        return ctx.toString();
    }

    private boolean isIdleChat(String message) {
        String lower = message.toLowerCase();
        for (String kw : CAR_TOPIC_WORDS) {
            if (lower.contains(kw.toLowerCase())) return false;
        }
        if (keywordBuilder.containsAnyKeyword(message)) return false;
        if (entityResolver.hasMatch(message)) return false;
        return true;
    }

    private List<ResolvedEntity> resolveEntities(String message) {
        return entityResolver.resolve(message);
    }

    /** 提取最近 10 轮对话历史文本（供 VagueQueryRouter L3 使用）。 */
    private String buildHistoryText(String userId) {
        List<org.springframework.ai.chat.messages.Message> messages = chatMemory.get(userId);
        if (messages == null || messages.isEmpty()) return "";
        // 取最近 10 条
        int start = Math.max(0, messages.size() - 10);
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < messages.size(); i++) {
            String text = messages.get(i).getText();
            if (text != null && !text.isBlank()) {
                sb.append(text).append("\n");
            }
        }
        return sb.toString();
    }

    private void logConversation(String userId, String userMessage, String response,
                                  int idleCount, boolean isIdle, boolean terminated,
                                  List<ResolvedEntity> matchedSeries) {
        String matchedJson = matchedSeries.stream()
                .map(ResolvedEntity::displayName)
                .map(this::escapeJson)
                .collect(Collectors.joining("\",\"", "[\"", "\"]"));
        String json = String.format(
                "{\"ts\":\"%s\",\"userId\":\"%s\",\"msg\":%s,\"reply\":%s,\"idleCount\":%d,\"isIdle\":%b,\"terminated\":%b,\"matched\":%s}",
                Instant.now().toString(), escapeJson(userId), escapeJson(userMessage),
                escapeJson(response != null ? response : ""), idleCount, isIdle, terminated, matchedJson);
        log.info(json);
    }

    private String escapeJson(String s) {
        if (s == null) return "\"\"";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\"";
    }

    /**
     * 处理用户消息（流式输出，SSE）。
     */
    public Flux<String> chatStream(String userId, String userMessage, String userIp) {
        List<ResolvedEntity> matchedSeries = resolveEntities(userMessage);

        int idleCount = idleCounters.getOrDefault(userId, 0);
        boolean isIdle = isIdleChat(userMessage) && matchedSeries.isEmpty();
        boolean terminated = false;

        if (isIdle) {
            idleCount = idleCounters.merge(userId, 1, Integer::sum);
            if (idleCount > IDLE_CHAT_LIMIT) {
                terminated = true;
                logConversation(userId, userMessage, IDLE_TERMINATION, idleCount, true, true, matchedSeries);
                return Flux.just(IDLE_TERMINATION);
            }
        } else {
            idleCounters.remove(userId);
            idleCount = 0;
            for (ResolvedEntity e : matchedSeries) {
                askCountTracker.recordMention(e.seriesKey());
            }
        }

        logConversation(userId, userMessage, "[stream]", idleCount, isIdle, terminated, matchedSeries);

        // VagueQueryRouter 路由（#13 ticket）
        String historyText = buildHistoryText(userId);
        MatchResult route = router.route(userMessage, historyText);

        if (route != null && route.needsConfirm()) {
            return Flux.just(route.followUpText());
        }

        // 两阶段检索（#4 ticket）
        String ragContext = retrieveContext(userMessage, matchedSeries);
        if (route != null && route.needsInference() && route.followUpText() != null) {
            ragContext = ragContext + "\n## 引导提示\n" + route.followUpText();
        }

        return chatClient.prompt()
                .user(userMessage)
                .system(ragContext)
                .advisors(a -> a.param("chat_memory_conversation_id", userId))
                .stream()
                .content();
    }

    public void resetIdleCounter(String userId) {
        idleCounters.remove(userId);
    }

    /** 安全读取 metadata 字符串值 */
    private static String metaStr(Document d, String key) {
        Object v = d.getMetadata().get(key);
        return v != null ? v.toString() : null;
    }
}
