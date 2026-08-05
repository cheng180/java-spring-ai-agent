package org.example.ai.impl;

import org.example.ai.impl.context.RetrievalContextAssembler;
import org.example.ai.impl.prompt.PromptTemplates;
import org.example.ai.impl.routing.IdleChatGate;
import org.example.ai.impl.routing.QueryClassification;
import org.example.ai.impl.tool.CarSalesTools;
import org.example.ai.knowledge.entity.EntityResolver;
import org.example.ai.knowledge.entity.ResolvedEntity;
import org.example.ai.knowledge.hotness.AskCountTracker;
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

    /** 空消息固定引导——空串不能进 ChatClient（Spring AI 对 user/system 文本断言非空） */
    private static final String BLANK_MESSAGE_REPLY = "您想了解点什么呢？说说想看的车或者预算，我帮您参谋～";

    private final Map<String, Integer> idleCounters = new ConcurrentHashMap<>();

    private final ChatClient chatClient;
    private final ChatMemory chatMemory;
    private final AskCountTracker askCountTracker;
    private final EntityResolver entityResolver;
    private final IdleChatGate idleChatGate;
    private final RetrievalContextAssembler contextAssembler;
    private final org.example.ai.impl.profile.CustomerProfileService profileService;

    public CarSalesAgent(ChatModel chatModel, CarSalesTools tools,
                         @Value("${company.name}") String companyName,
                         AskCountTracker askCountTracker,
                         EntityResolver entityResolver,
                         IdleChatGate idleChatGate,
                         RetrievalContextAssembler contextAssembler,
                         org.example.ai.impl.profile.CustomerProfileService profileService) {
        this.askCountTracker = askCountTracker;
        this.entityResolver = entityResolver;
        this.idleChatGate = idleChatGate;
        this.contextAssembler = contextAssembler;
        this.profileService = profileService;

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
     * 处理用户消息（Web 入口；画像渠道 = web）。
     *
     * @param userId      用户标识（会话隔离）
     * @param userMessage 用户消息文本
     * @param userIp      客户端 IP（契约保留；当前实现未消费）
     */
    @Override
    public String chat(String userId, String userMessage, String userIp) {
        profileService.capture("web", userId, userMessage); // #39 画像抓取（失败不阻断）
        return doChat(userId, userMessage, userIp);
    }

    private String doChat(String userId, String userMessage, String userIp) {
        if (userMessage == null || userMessage.isBlank()) {
            logConversation(userId, userMessage == null ? "" : userMessage, BLANK_MESSAGE_REPLY,
                    0, false, false, List.of(), null, null, false);
            return BLANK_MESSAGE_REPLY;
        }

        List<ResolvedEntity> matchedSeries = resolveEntities(userMessage);

        int idleCount = idleCounters.getOrDefault(userId, 0);
        boolean isIdle = isIdle(userMessage, matchedSeries);
        boolean terminated = false;
        String response;
        QueryClassification classification = null;
        org.example.ai.impl.routing.VagueAssessment vague = null;
        boolean guided = false;

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

            // 两阶段检索（#4 ticket）+ 程度评定/引导（#41 ticket）
            RetrievalContextAssembler.Result assembled =
                    contextAssembler.retrieveContext(userMessage, matchedSeries, userId);
            classification = assembled.classification();
            vague = assembled.vague();
            guided = assembled.guidance() != null && !assembled.guidance().isBlank();

            // 空上下文不挂 system（Spring AI 断言文本非空；全检索落空时 buildContext 返回 ""）；
            // 引导段落经 systemText() 附加（#41），组装字节流本身不变
            String systemText = assembled.systemText();
            ChatClient.ChatClientRequestSpec spec = chatClient.prompt()
                    .user(userMessage)
                    .advisors(a -> a.param("chat_memory_conversation_id", userId));
            if (systemText != null && !systemText.isBlank()) {
                spec = spec.system(systemText);
            }
            response = spec.call().content();
        }

        logConversation(userId, userMessage, response, idleCount, isIdle, terminated,
                matchedSeries, classification, vague, guided);
        return response;
    }

    /**
     * 处理 macan 侧客户消息（无状态集成，见 docs/macan-ai-integration-plan.md）。
     *
     * macan 每次请求都带上 Redis 全量历史，且 IAIService.chatReply(List&lt;Message&gt;) 接口
     * 无法透传 userId，因此本路径不依赖 AI 项目自身的跨请求记忆：用一次性会话 id，先把
     * macan 历史灌入 ChatMemory（供 MemoryAdvisor 使用），用完即清空，
     * 避免不同客户串话、也避免内存随请求数堆积。
     *
     * <p>画像抓取（#39）当前仅覆盖 web 入口；macan 通道待其稳定客户 ID 透传后接入。</p>
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
            // 走 doChat 而非 chat()：macan 通道暂不做画像抓取，避免以一次性会话 id 误建 web 画像
            return doChat(conversationId, userMessage, "macan-server");
        } finally {
            chatMemory.clear(conversationId);
        }
    }

    /**
     * 闲聊判定（#29 黑名单制）：只有命中明确闲聊黑名单才进闲聊分支；
     * 提到车系实体的消息永不判闲聊。chat()/chatStream() 两个入口共用。
     */
    private boolean isIdle(String userMessage, List<ResolvedEntity> matchedSeries) {
        return idleChatGate.isIdleChat(userMessage) && matchedSeries.isEmpty();
    }

    private List<ResolvedEntity> resolveEntities(String message) {
        return entityResolver.resolve(message);
    }

    private void logConversation(String userId, String userMessage, String response,
                                  int idleCount, boolean isIdle, boolean terminated,
                                  List<ResolvedEntity> matchedSeries,
                                  QueryClassification classification,
                                  org.example.ai.impl.routing.VagueAssessment vague,
                                  boolean guided) {
        String matchedJson = matchedSeries.stream()
                .map(ResolvedEntity::displayName)
                .map(this::escapeJson)
                .collect(Collectors.joining("\",\"", "[\"", "\"]"));
        // 分类级别归因（《回复过长问题解决评估文档》加固建议 1）：
        // NONE=闲聊/未分级；BRAND/FAMILY/SERIES/UNRESTRICTED 见 QueryLevel
        String level = classification == null ? "NONE" : classification.level().name();
        String levelBrand = classification == null || classification.brand() == null
                ? "" : classification.brand();
        // 程度归因（#41，#35 决策 9）：置信度分数/档位/是否注入引导入日志，
        // 上线后任何一轮回复可归因，分数供阈值标定（#40）；vagueTier=-1 表未评分
        int vagueTier = vague == null ? -1 : vague.tier();
        double vagueConf = vague == null ? -1.0 : vague.confidence();
        String json = String.format(
                "{\"ts\":\"%s\",\"userId\":\"%s\",\"msg\":%s,\"reply\":%s,\"idleCount\":%d,\"isIdle\":%b,\"terminated\":%b,\"matched\":%s,\"level\":\"%s\",\"levelBrand\":%s,\"vagueTier\":%d,\"vagueConf\":%.2f,\"guided\":%b}",
                Instant.now().toString(), escapeJson(userId), escapeJson(userMessage),
                escapeJson(response != null ? response : ""), idleCount, isIdle, terminated,
                matchedJson, level, escapeJson(levelBrand), vagueTier, vagueConf, guided);
        log.info(json);
    }

    private String escapeJson(String s) {
        if (s == null) return "\"\"";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\"";
    }

    /**
     * 处理用户消息（流式输出，SSE；画像渠道 = web）。
     */
    @Override
    public Flux<String> chatStream(String userId, String userMessage, String userIp) {
        if (userMessage == null || userMessage.isBlank()) {
            logConversation(userId, userMessage == null ? "" : userMessage, "[stream-blank]",
                    0, false, false, List.of(), null, null, false);
            return Flux.just(BLANK_MESSAGE_REPLY);
        }

        profileService.capture("web", userId, userMessage); // #39 画像抓取（失败不阻断）

        List<ResolvedEntity> matchedSeries = resolveEntities(userMessage);

        int idleCount = idleCounters.getOrDefault(userId, 0);
        boolean isIdle = isIdle(userMessage, matchedSeries);
        boolean terminated = false;

        if (isIdle) {
            idleCount = idleCounters.merge(userId, 1, Integer::sum);
            if (idleCount > IDLE_CHAT_LIMIT) {
                terminated = true;
                logConversation(userId, userMessage, IDLE_TERMINATION, idleCount, true, true,
                        matchedSeries, null, null, false);
                return Flux.just(IDLE_TERMINATION);
            }
        } else {
            idleCounters.remove(userId);
            idleCount = 0;
            for (ResolvedEntity e : matchedSeries) {
                askCountTracker.recordMention(e.seriesKey());
            }
        }

        // 两阶段检索（#4 ticket）+ 程度评定/引导（#41）——日志在检索后记录，携带归因字段
        RetrievalContextAssembler.Result assembled =
                contextAssembler.retrieveContext(userMessage, matchedSeries, userId);
        boolean guided = assembled.guidance() != null && !assembled.guidance().isBlank();
        logConversation(userId, userMessage, "[stream]", idleCount, isIdle, terminated,
                matchedSeries, assembled.classification(), assembled.vague(), guided);

        // 空上下文不挂 system（与 chat() 同步入口同守卫）；引导经 systemText() 附加（#41）
        String systemText = assembled.systemText();
        ChatClient.ChatClientRequestSpec spec = chatClient.prompt()
                .user(userMessage)
                .advisors(a -> a.param("chat_memory_conversation_id", userId));
        if (systemText != null && !systemText.isBlank()) {
            spec = spec.system(systemText);
        }
        return spec.stream().content();
    }

    public void resetIdleCounter(String userId) {
        idleCounters.remove(userId);
    }
}
