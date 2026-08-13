package org.example.ai.impl;

import org.example.ai.impl.context.RetrievalContextAssembler;
import org.example.ai.impl.conversation.ConversationGuidance;
import org.example.ai.impl.conversation.ConversationGuidanceBuilder;
import org.example.ai.impl.prompt.PromptTemplates;
import org.example.ai.impl.routing.IdleChatGate;
import org.example.ai.impl.routing.QueryClassification;
import org.example.ai.impl.tool.CarSalesTools;
import org.example.ai.knowledge.entity.EntityResolver;
import org.example.ai.knowledge.entity.ResolvedEntity;
import org.example.ai.knowledge.hotness.AskCountTracker;
import org.example.ai.ChatService;
import org.example.ai.config.observability.ObservationSupport;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
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

    /** 对话记忆窗口（MessageWindowChatMemory）：40 轮对话内的消息都进上下文，扩大防"说两句就忘"。 */
    private static final int MEMORY_MAX_MESSAGES = 80;
    private static final int IDLE_CHAT_LIMIT = 3;
    private static final String IDLE_TERMINATION = "买车的事随时找我，先不打扰您了～有需要再聊！";

    /** 空消息固定引导——空串不能进 ChatClient（Spring AI 对 user/system 文本断言非空） */
    private static final String BLANK_MESSAGE_REPLY = "您想了解点什么呢？说说想看的车或者预算，我帮您参谋～";

    // ---- Langfuse 链路观测：一轮对话 = 一条以 chat-turn 为根的完整 trace ----
    /** 对话轮次根观测名（Langfuse trace 名取自根 span 名） */
    private static final String TURN_OBSERVATION = "chat-turn";
    /** Langfuse v4 OTLP 映射属性：trace 级会话/用户身份（低基数，可筛选） */
    private static final String ATTR_SESSION_ID = "langfuse.session.id";
    private static final String ATTR_USER_ID = "langfuse.user.id";
    /** Langfuse v4 OTLP 映射属性：observation 级输入/输出（挂在根 span 即整轮对话的输入/输出） */
    private static final String ATTR_INPUT = "langfuse.observation.input";
    private static final String ATTR_OUTPUT = "langfuse.observation.output";

    private final Map<String, Integer> idleCounters = new ConcurrentHashMap<>();

    private final ChatClient chatClient;
    private final ChatMemory chatMemory;
    private final AskCountTracker askCountTracker;
    private final EntityResolver entityResolver;
    private final IdleChatGate idleChatGate;
    private final ConversationGuidanceBuilder guidanceBuilder;
    private final RetrievalContextAssembler contextAssembler;
    private final ObservationRegistry observationRegistry;

    public CarSalesAgent(ChatModel chatModel, CarSalesTools tools,
                         @Value("${company.name}") String companyName,
                         AskCountTracker askCountTracker,
                         EntityResolver entityResolver,
                         IdleChatGate idleChatGate,
                         ConversationGuidanceBuilder guidanceBuilder,
                         RetrievalContextAssembler contextAssembler,
                         ObservationRegistry observationRegistry) {
        this.askCountTracker = askCountTracker;
        this.entityResolver = entityResolver;
        this.idleChatGate = idleChatGate;
        this.guidanceBuilder = guidanceBuilder;
        this.contextAssembler = contextAssembler;
        this.observationRegistry = observationRegistry;

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
     * <p>整轮对话包在一条 {@code chat-turn} 根观测里：路由、检索（embedding/chroma）、
     * LLM 调用（含工具循环）全部挂在同一条 trace 下，并带上 Langfuse 的
     * session/user/输入/输出属性。Web 路径会话 ID 即 userId。</p>
     *
     * @param userId      用户标识（会话隔离）
     * @param userMessage 用户消息文本
     * @param userIp      客户端 IP（GeoLocator 定位用，FixedGeoLocator 当前忽略此参数）
     */
    @Override
    public String chat(String userId, String userMessage, String userIp) {
        Observation turn = startTurnObservation(userId, userId, userMessage);
        try (Observation.Scope ignored = turn.openScope()) {
            String response = chatInternal(userId, userMessage, userIp);
            setTurnOutput(turn, response);
            return response;
        } catch (RuntimeException e) {
            turn.error(e);
            throw e;
        } finally {
            turn.stop();
        }
    }

    private String chatInternal(String userId, String userMessage, String userIp) {
        if (userMessage == null || userMessage.isBlank()) {
            logConversation(userId, userMessage == null ? "" : userMessage, BLANK_MESSAGE_REPLY,
                    0, false, false, List.of(), null);
            return BLANK_MESSAGE_REPLY;
        }

        List<ResolvedEntity> matchedSeries = ObservationSupport.call(observationRegistry, "agent.entity-resolution", null, () -> resolveEntities(userMessage));

        int idleCount = idleCounters.getOrDefault(userId, 0);
        boolean isIdle = isIdle(userMessage, matchedSeries);
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

            String historyText = buildHistoryText(userId);
            final List<ResolvedEntity> inputEntities = matchedSeries;
            ConversationGuidance guidance = ObservationSupport.call(
                    observationRegistry, "agent.conversation-understanding", null,
                    () -> guidanceBuilder.build(userMessage, historyText, inputEntities));
            matchedSeries = guidance.entities();

            // 模糊语义只提供内部指导和有效检索词，不再绕过主 Agent 直接回答。
            RetrievalContextAssembler.Result assembled =
                    contextAssembler.retrieveContext(guidance.retrievalQuery(), matchedSeries);
            classification = assembled.classification();
            String ragContext = assembled.context();
            if (!guidance.prompt().isBlank()) {
                ragContext = ragContext + "\n" + guidance.prompt();
            }

            // 空上下文不挂 system（Spring AI 断言文本非空；全检索落空时 buildContext 返回 ""）
            ChatClient.ChatClientRequestSpec spec = chatClient.prompt()
                    .user(userMessage)
                    .advisors(a -> a.param("chat_memory_conversation_id", userId));
            if (ragContext != null && !ragContext.isBlank()) {
                spec = spec.system(ragContext);
            }
            final ChatClient.ChatClientRequestSpec llmSpec = spec;
            response = ObservationSupport.call(observationRegistry, "agent.llm", null, () -> llmSpec.call().content());
            // 价格兜底（第三道防线）：客户未问价但模型仍报了价格（可能是模型训练知识）
            // → 用修正指令让模型重写为"一句话概括 + 引导"。
            if (!isPriceInquiry(userMessage) && containsPrice(response)) {
                response = ObservationSupport.call(observationRegistry, "agent.llm-rewrite", null,
                        () -> rewriteWithoutPrice(chatClient, userId, userMessage));
            }
            // 销售线索兜底：客户已进入购车意向阶段（约试驾/到店/选定/问价）但回复没要联系方式
            // → 在末尾自然追加留联系方式引导（prompt 软约束压不住，代码兜底保证线索不漏）。
            // 防重复：客户已给手机号 / 回复已确认联系方式 / 本会话已追加过 → 不再要。
            boolean userLeftPhone = PHONE_PATTERN.matcher(userMessage == null ? "" : userMessage).find();
            if (userLeftPhone) {
                contactRequested.add(userId);
            }
            if (hasPurchaseIntent(userMessage, response)
                    && !containsContactRequest(response)
                    && !userLeftPhone
                    && !contactRequested.contains(userId)) {
                response = response + "\n\n" + CONTACT_LEAD_HINT;
                contactRequested.add(userId);
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
     * macan 历史灌入 ChatMemory（供 MemoryAdvisor 与 Agent 内部对话理解使用），用完即清空，
     * 避免不同客户串话、也避免内存随请求数堆积。
     *
     * @param conversationId 一次性会话 id（每请求唯一）
     * @param userMessage    当前客户消息
     * @param history        macan Redis 历史（user/assistant 交替，不含当前消息；system 已过滤）
     */
    @Override
    public String chatWithHistory(String conversationId, String userMessage,
                                  List<org.springframework.ai.chat.messages.Message> history) {
        // macan 路径：会话维度落在 macan userId（conversationId 的 UUID 后缀仅用于并发隔离），
        // 同一客户的多轮对话在 Langfuse 里归入同一 session
        String sessionId = extractMacanSession(conversationId);
        Observation turn = startTurnObservation(conversationId, sessionId, userMessage);
        try (Observation.Scope ignored = turn.openScope()) {
            chatMemory.clear(conversationId);
            if (history != null && !history.isEmpty()) {
                chatMemory.add(conversationId, history);
            }
            String response = chatInternal(conversationId, userMessage, "macan-server");
            setTurnOutput(turn, response);
            return response;
        } catch (RuntimeException e) {
            turn.error(e);
            throw e;
        } finally {
            chatMemory.clear(conversationId);
            turn.stop();
        }
    }

    /** conversationId = &lt;macan userId&gt;-&lt;uuid&gt;；取 userId 段作会话 ID（无后缀则原样返回） */
    private static String extractMacanSession(String conversationId) {
        int cut = conversationId.length() - 37; // "-" + 36 位 UUID
        if (cut > 0 && conversationId.charAt(cut) == '-') {
            return conversationId.substring(0, cut);
        }
        return conversationId;
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

    /** 历史文本总长上限（字符）：多轮长回复累积会把输入上下文撑爆（回复过长根因之一）。 */
    private static final int MAX_HISTORY_CHARS = 1500;

    /** 提取最近 10 轮对话历史文本（供 Agent 内部对话理解使用），总长不超过 MAX_HISTORY_CHARS。 */
    private String buildHistoryText(String userId) {
        List<org.springframework.ai.chat.messages.Message> messages = chatMemory.get(userId);
        if (messages == null || messages.isEmpty()) return "";
        // 取最近 10 条；从最新一条往回累计，靠近当前对话的内容优先保留，超长截断更早的
        int start = Math.max(0, messages.size() - 10);
        List<String> recent = new ArrayList<>();
        int total = 0;
        for (int i = messages.size() - 1; i >= start && total < MAX_HISTORY_CHARS; i--) {
            String text = messages.get(i).getText();
            if (text == null || text.isBlank()) continue;
            int budget = MAX_HISTORY_CHARS - total;
            recent.add(text.length() > budget ? text.substring(text.length() - budget) : text);
            total += Math.min(text.length(), budget);
        }
        Collections.reverse(recent);
        return String.join("\n", recent);
    }

    private void logConversation(String userId, String userMessage, String response,
                                  int idleCount, boolean isIdle, boolean terminated,
                                  List<ResolvedEntity> matchedSeries,
                                  QueryClassification classification) {
        String matchedJson = matchedSeries.stream()
                .map(ResolvedEntity::displayName)
                .map(CarSalesAgent::escapeJson)
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

        // 分类级别/闲聊状态同时写进当前对话轮次观测（Langfuse observation metadata，可筛选）
        Observation current = observationRegistry.getCurrentObservation();
        if (current != null) {
            current.highCardinalityKeyValue("langfuse.observation.metadata.level", level);
            current.highCardinalityKeyValue("langfuse.observation.metadata.idle", String.valueOf(isIdle));
        }
    }

    // ---- 价格兜底（渐进式披露第三道防线） ----

    /** 手机号（客户主动留下时视为已有联系方式，线索兜底不再重复要）。 */
    private static final java.util.regex.Pattern PHONE_PATTERN = java.util.regex.Pattern.compile("1[3-9]\\d{9}");

    /** 已留过联系方式/已追加过引导的用户（本进程内），防止线索兜底每轮重复要电话。 */
    private final java.util.Set<String> contactRequested = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 销售线索引导：客户意向明确但回复没留联系方式时，追加这句（prompt 软约束的代码兜底）。
     *  电话 + 所在区域 = 完整销售线索（地址用于就近门店对接）。 */
    private static final String CONTACT_LEAD_HINT =
            "方便的话留个电话和您所在的区域（哪个城市/区），我安排专门的销售和您对接，还能帮您申请一些优惠～";

    /** 回复是否已包含留联系方式引导（电话或地址；含"手机号/已记下/收到"等确认措辞）。 */
    private static boolean containsContactRequest(String reply) {
        if (reply == null) return false;
        return reply.contains("电话") || reply.contains("微信") || reply.contains("联系方式")
                || reply.contains("留个") || reply.contains("号码") || reply.contains("手机")
                || reply.contains("记下") || reply.contains("收到")
                || reply.contains("所在城市") || reply.contains("所在区域")
                || reply.contains("住哪") || reply.contains("在哪边") || reply.contains("哪个区")
                || reply.contains("常驻");
    }

    /** 是否已进入"购车意向明确"阶段（约试驾/到店/选定/问价/提车等）。 */
    private static boolean hasPurchaseIntent(String userMessage, String reply) {
        String m = userMessage == null ? "" : userMessage;
        String r = reply == null ? "" : reply;
        for (String w : new String[]{"试驾", "到店", "约", "买", "定", "看车", "优惠", "提车", "现车"}) {
            if (m.contains(w) || r.contains(w)) return true;
        }
        return false;
    }

    /** 客户消息是否已进入"需要价格"的层级（与 RetrievalContextAssembler.isPriceInquiry 对齐）。 */
    private static boolean isPriceInquiry(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) return false;
        String m = userMessage;
        return m.contains("多少钱") || m.contains("怎么卖") || m.contains("价格")
                || m.contains("报价") || m.contains("优惠") || m.contains("便宜")
                || m.contains("预算") || m.contains("砍价") || m.contains("贵")
                || m.contains("对比") || m.contains("哪个好") || m.contains("区别")
                || java.util.regex.Pattern.matches(".*\\d+\\s*万.*", m);
    }

    /** 回复是否包含价格数字（如 24.98万 / 46.98万）。 */
    private static boolean containsPrice(String reply) {
        if (reply == null) return false;
        return java.util.regex.Pattern.compile("\\d+(\\.\\d+)?\\s*万").matcher(reply).find();
    }

    /** 客户未问价却报了价格 → 用修正指令让模型重写为"一句话概括 + 引导"。 */
    private String rewriteWithoutPrice(ChatClient chatClient, String userId, String userMessage) {
        String instruction = """
                上一条回复因客户尚未问价却主动报了价格而被拦截，请重写你的回复，要求：
                1. 只做一句话概括（如"问界M9，华为智驾加持的旗舰SUV"），最多提到 2~3 个车型；
                2. 末尾用一句引导让客户继续（"您对哪款感兴趣？或者说说您的预算和用车需求，我帮您匹配"）；
                3. 严禁出现任何价格数字（xx万），严禁罗列颜色、配置等明细。""";
        return chatClient.prompt()
                .user(userMessage)
                .system(instruction)
                .advisors(a -> a.param("chat_memory_conversation_id", userId))
                .call()
                .content();
    }

    private static String escapeJson(String s) {
        if (s == null) return "\"\"";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\"";
    }

    /**
     * 处理用户消息（流式输出，SSE）。
     *
     * <p>链路与同步入口一致：一条 {@code chat-turn} 根观测覆盖整轮。同步段
     * （闲聊判定/路由/检索）在观测 scope 内执行；LLM 流式调用发生在订阅期，
     * 用 {@code Flux.defer + openScope} 让订阅发生在 scope 内，保证 generation
     * span 挂到本 trace 而不是漂成孤儿根（修复前流式一轮会碎成 3 条 trace）。</p>
     */
    @Override
    public Flux<String> chatStream(String userId, String userMessage, String userIp) {
        Observation turn = startTurnObservation(userId, userId, userMessage);
        try (Observation.Scope ignored = turn.openScope()) {
            if (userMessage == null || userMessage.isBlank()) {
                logConversation(userId, userMessage == null ? "" : userMessage, "[stream-blank]",
                        0, false, false, List.of(), null);
                return Flux.just(BLANK_MESSAGE_REPLY)
                        .doFinally(signal -> stopTurnWithOutput(turn, BLANK_MESSAGE_REPLY));
            }

            List<ResolvedEntity> matchedSeries = ObservationSupport.call(observationRegistry, "agent.entity-resolution", null, () -> resolveEntities(userMessage));

            int idleCount = idleCounters.getOrDefault(userId, 0);
            boolean isIdle = isIdle(userMessage, matchedSeries);
            boolean terminated = false;

            if (isIdle) {
                idleCount = idleCounters.merge(userId, 1, Integer::sum);
                if (idleCount > IDLE_CHAT_LIMIT) {
                    terminated = true;
                    logConversation(userId, userMessage, IDLE_TERMINATION, idleCount, true, true,
                            matchedSeries, null);
                    return Flux.just(IDLE_TERMINATION)
                            .doFinally(signal -> stopTurnWithOutput(turn, IDLE_TERMINATION));
                }
            } else {
                idleCounters.remove(userId);
                idleCount = 0;
                for (ResolvedEntity e : matchedSeries) {
                    askCountTracker.recordMention(e.seriesKey());
                }
            }

            String historyText = buildHistoryText(userId);
            final List<ResolvedEntity> inputEntities = matchedSeries;
            ConversationGuidance guidance = ObservationSupport.call(
                    observationRegistry, "agent.conversation-understanding", null,
                    () -> guidanceBuilder.build(userMessage, historyText, inputEntities));
            matchedSeries = guidance.entities();
            // 两阶段检索（#4 ticket）——日志在检索后记录，携带分类级别归因
            RetrievalContextAssembler.Result assembled =
                    contextAssembler.retrieveContext(guidance.retrievalQuery(), matchedSeries);
            logConversation(userId, userMessage, "[stream]", idleCount, isIdle, terminated,
                    matchedSeries, assembled.classification());
            String ragContext = assembled.context();
            if (!guidance.prompt().isBlank()) {
                ragContext = ragContext + "\n" + guidance.prompt();
            }

            // 空上下文不挂 system（与 chat() 同步入口同守卫）；spec 须 effectively final 供订阅期 lambda 使用
            ChatClient.ChatClientRequestSpec spec = chatClient.prompt()
                    .user(userMessage)
                    .advisors(a -> a.param("chat_memory_conversation_id", userId));
            final ChatClient.ChatClientRequestSpec streamSpec =
                    (ragContext != null && !ragContext.isBlank()) ? spec.system(ragContext) : spec;

            // 订阅发生在 turn scope 内 → ChatClient 链（记忆 advisor / LLM 流式 generation）
            // 创建观测时能拿到父上下文。注意必须用 Flux.using 而非 defer：defer 的 supplier
            // 返回内部 Flux 后 scope 即关闭，真正的订阅（及 generation 观测创建）发生在
            // scope 之外 → 孤儿 span；using 的资源（scope）在整个订阅链生命周期内保持打开。
            StringBuilder collected = new StringBuilder();
            return Flux.using(turn::openScope,
                            scope -> ObservationSupport.call(observationRegistry, "agent.llm", null, () -> streamSpec.stream().content()),
                            Observation.Scope::close)
                    .doOnNext(collected::append)
                    .doOnError(turn::error)
                    .doFinally(signal -> stopTurnWithOutput(turn, collected.toString()));
        } catch (RuntimeException e) {
            turn.error(e);
            turn.stop();
            throw e;
        }
    }

    // ---- 对话轮次观测辅助 ----

    /**
     * 创建并启动一条对话轮次根观测。
     *
     * <p>属性按 Langfuse v4 OTLP 映射（已对本机自托管实例实证）：
     * {@code langfuse.session.id}/{@code langfuse.user.id} 成为 trace 的
     * sessionId/userId（会话/用户维度聚合）；{@code langfuse.observation.input/output}
     * 挂在根 span 即整轮对话的输入/输出。</p>
     */
    private Observation startTurnObservation(String userId, String sessionId, String userMessage) {
        Observation observation = Observation.createNotStarted(TURN_OBSERVATION, observationRegistry)
                .highCardinalityKeyValue(ATTR_SESSION_ID, sessionId == null ? "" : sessionId)
                .highCardinalityKeyValue(ATTR_USER_ID, userId == null ? "" : userId);
        observation.highCardinalityKeyValue(ATTR_INPUT,
                "{\"message\":" + escapeJson(userMessage == null ? "" : userMessage) + "}");
        observation.start();
        return observation;
    }

    /** 写整轮输出并停止根观测（doFinally 回调专用，不抛出） */
    private static void stopTurnWithOutput(Observation turn, String response) {
        try {
            setTurnOutput(turn, response);
        } finally {
            turn.stop();
        }
    }

    private static void setTurnOutput(Observation turn, String response) {
        turn.highCardinalityKeyValue(ATTR_OUTPUT,
                "{\"reply\":" + escapeJson(response == null ? "" : response) + "}");
    }

    public void resetIdleCounter(String userId) {
        idleCounters.remove(userId);
    }
}
