package org.example.ai.impl;

import org.example.ai.impl.context.RetrievalContextAssembler;
import org.example.ai.impl.prompt.PromptTemplates;
import org.example.ai.impl.routing.QueryClassification;
import org.example.ai.impl.tool.CarSalesTools;
import org.example.ai.config.DynamicKeywordBuilder;
import org.example.ai.knowledge.entity.EntityResolver;
import org.example.ai.knowledge.entity.ResolvedEntity;
import org.example.ai.knowledge.hotness.AskCountTracker;
import org.example.ai.impl.location.GeoLocation;
import org.example.ai.impl.location.GeoLocator;
import org.example.ai.impl.location.StoreLocator;
import org.example.ai.impl.routing.MatchResult;
import org.example.ai.impl.routing.VagueQueryRouter;
import org.example.ai.ChatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import reactor.core.publisher.Flux;

/**
 * 汽车销售 Agent —— {@link ChatService} 的唯一实现。
 *
 * <p>LLM 自主决策，代码只提供工具。所有对话入口最终汇聚于此。</p>
 *
 * <p>检索（#4 ticket）：两阶段 ——
 * 1. 相似度召回：子块 + 百科/话术，排除父块（避免父块挤占 topK）
 * 2. 父块确定性展开：命中车系的父块（热度/价格区间/车型列表）必达，
 *    EntityResolver 直接命中的车系也无条件附加父块</p>
 */
@Component
public class CarSalesAgent implements ChatService {

    private static final Logger log = LoggerFactory.getLogger(CarSalesAgent.class);

    private static final int MEMORY_MAX_MESSAGES = 40;
    private static final int IDLE_CHAT_LIMIT = 3;
    private static final String IDLE_TERMINATION = "买车的事随时找我，先不打扰您了～有需要再聊！";

    private final Map<String, Integer> idleCounters = new ConcurrentHashMap<>();

    private final ChatClient chatClient;
    private final ChatMemory chatMemory;
    private final AskCountTracker askCountTracker;
    private final EntityResolver entityResolver;
    private final DynamicKeywordBuilder keywordBuilder;
    private final VagueQueryRouter router;
    private final GeoLocator geoLocator;
    private final StoreLocator storeLocator;
    private final RetrievalContextAssembler contextAssembler;

    /** 通用汽车话题词（不含品牌/车系名——那一侧由 DynamicKeywordBuilder + EntityResolver 覆盖，#12 ticket） */
    private static final Set<String> CAR_TOPIC_WORDS = Set.of(
            "买车", "购车", "看车", "试驾", "订车", "提车",
            "多少钱", "报价", "价格", "售价", "指导价", "落地价", "优惠",
            "库存", "现车", "有货", "多久提车",
            "suv", "mpv", "轿车", "新能源", "纯电", "混动", "油车", "电车", "增程",
            "耗油", "续航", "配置", "性能", "空间",
            "门店", "地址", "电话", "在哪里", "在哪", "怎么去",
            "到店", "预约", "试驾车", "转人工", "销售",
            "推荐", "预算", "家用", "代步", "通勤", "性价比",
            // 汽车配置/特征词（防止"全景天窗""有没有混动版"等被判为闲聊）
            "天窗", "全景", "真皮", "座椅", "加热", "通风", "气囊",
            "雷达", "影像", "导航", "巡航", "自动泊车", "日行灯",
            "排量", "油耗", "加速", "极速", "马力", "扭矩", "驱动",
            "四驱", "两驱", "前驱", "后驱", "手动挡", "自动挡",
            "跑车", "超跑", "敞篷", "越野", "皮卡", "旅行版",
            "有没有", "有没有卖", "有没有现车", "能不能", "可不可以"
    );

    public CarSalesAgent(ChatModel chatModel, CarSalesTools tools,
                         @Value("${company.name}") String companyName,
                         AskCountTracker askCountTracker,
                         EntityResolver entityResolver,
                         DynamicKeywordBuilder keywordBuilder,
                         VagueQueryRouter router,
                         GeoLocator geoLocator,
                         StoreLocator storeLocator,
                         RetrievalContextAssembler contextAssembler) {
        this.askCountTracker = askCountTracker;
        this.entityResolver = entityResolver;
        this.keywordBuilder = keywordBuilder;
        this.router = router;
        this.geoLocator = geoLocator;
        this.storeLocator = storeLocator;
        this.contextAssembler = contextAssembler;

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

        log.info("CarSalesAgent 初始化完成（公司：{}, 设施：两阶段检索（组装器委托）, EntityResolver：{} 别名）",
                companyName, entityResolver.aliasCount());
    }

    /**
     * 处理用户消息。
     *
     * @param userId      用户标识（会话隔离）
     * @param userMessage 用户消息文本
     * @param userIp      客户端 IP（GeoLocator 定位用，FixedGeoLocator 当前忽略此参数）
     */
    @Override
    public String chat(String userId, String userMessage, String userIp) {
        List<ResolvedEntity> matchedSeries = resolveEntities(userMessage);

        int idleCount = idleCounters.getOrDefault(userId, 0);
        boolean isIdle = isIdleChat(userMessage) && matchedSeries.isEmpty();
        boolean terminated = false;
        String response;
        QueryClassification classification = null;

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
            MatchResult route = router.route(userMessage, historyText, userIp);

            if (route != null && route.needsConfirm()) {
                // BRAND 或 VAGUE+确认 → 直接返回追问文本，不调 LLM
                response = route.followUpText();
            } else {
                // EXACT / null → 走两阶段检索（#4 ticket）
                RetrievalContextAssembler.Result assembled =
                        contextAssembler.retrieveContext(userMessage, matchedSeries);
                classification = assembled.classification();
                String ragContext = assembled.context();

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

        logConversation(userId, userMessage, response, idleCount, isIdle, terminated,
                matchedSeries, classification);
        return response;
    }

    /**
     * 处理 macan 侧客户消息（无状态集成，见 docs/macan-ai-integration-plan.md）。
     *
     * macan 每次请求都带上 Redis 全量历史，且 IAIService.chatReply(List&lt;Message&gt;) 接口
     * 无法透传 userId，因此本路径不依赖 AI 项目自身的跨请求记忆：用一次性会话 id，先把
     * macan 历史灌入 ChatMemory（供 MemoryAdvisor 与 VagueQueryRouter 使用），用完即清空，
     * 避免不同客户串话、也避免内存随请求数堆积。
     *
     * @param conversationId 一次性会话 id（每请求唯一）
     * @param userMessage    当前客户消息
     * @param history        macan Redis 历史（user/assistant 交替，不含当前消息；system 已过滤）
     */
    @Override
    public String chatWithHistory(String conversationId, String userMessage,
                                  List<org.springframework.ai.chat.messages.Message> history) {
        try {
            chatMemory.clear(conversationId);
            if (history != null && !history.isEmpty()) {
                chatMemory.add(conversationId, history);
            }
            return chat(conversationId, userMessage, "macan-server");
        } finally {
            chatMemory.clear(conversationId);
        }
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
                                  List<ResolvedEntity> matchedSeries,
                                  QueryClassification classification) {
        String matchedJson = matchedSeries.stream()
                .map(ResolvedEntity::displayName)
                .map(this::escapeJson)
                .collect(Collectors.joining("\",\"", "[\"", "\"]"));
        // 分类级别归因（《回复过长问题解决评估文档》加固建议 1）：
        // NONE=闲聊/未分级；BRAND/FAMILY/SERIES/UNRESTRICTED 见 QueryLevel
        String level = classification == null ? "NONE" : classification.level().name();
        String levelBrand = classification == null || classification.brand() == null
                ? "" : classification.brand();
        String json = String.format(
                "{\"ts\":\"%s\",\"userId\":\"%s\",\"msg\":%s,\"reply\":%s,\"idleCount\":%d,\"isIdle\":%b,\"terminated\":%b,\"matched\":%s,\"level\":\"%s\",\"levelBrand\":%s}",
                Instant.now().toString(), escapeJson(userId), escapeJson(userMessage),
                escapeJson(response != null ? response : ""), idleCount, isIdle, terminated,
                matchedJson, level, escapeJson(levelBrand));
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
    @Override
    public Flux<String> chatStream(String userId, String userMessage, String userIp) {
        List<ResolvedEntity> matchedSeries = resolveEntities(userMessage);

        int idleCount = idleCounters.getOrDefault(userId, 0);
        boolean isIdle = isIdleChat(userMessage) && matchedSeries.isEmpty();
        boolean terminated = false;

        if (isIdle) {
            idleCount = idleCounters.merge(userId, 1, Integer::sum);
            if (idleCount > IDLE_CHAT_LIMIT) {
                terminated = true;
                logConversation(userId, userMessage, IDLE_TERMINATION, idleCount, true, true,
                        matchedSeries, null);
                return Flux.just(IDLE_TERMINATION);
            }
        } else {
            idleCounters.remove(userId);
            idleCount = 0;
            for (ResolvedEntity e : matchedSeries) {
                askCountTracker.recordMention(e.seriesKey());
            }
        }

        // VagueQueryRouter 路由（#13 ticket）
        String historyText = buildHistoryText(userId);
        MatchResult route = router.route(userMessage, historyText, userIp);

        if (route != null && route.needsConfirm()) {
            logConversation(userId, userMessage, "[stream]", idleCount, isIdle, terminated,
                    matchedSeries, null);
            return Flux.just(route.followUpText());
        }

        // 两阶段检索（#4 ticket）——日志在检索后记录，携带分类级别归因
        RetrievalContextAssembler.Result assembled =
                contextAssembler.retrieveContext(userMessage, matchedSeries);
        logConversation(userId, userMessage, "[stream]", idleCount, isIdle, terminated,
                matchedSeries, assembled.classification());
        String ragContext = assembled.context();
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
}
