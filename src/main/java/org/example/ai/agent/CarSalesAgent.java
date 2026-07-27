package org.example.ai.agent;

import org.example.ai.agent.prompt.PromptTemplates;
import org.example.ai.agent.tool.CarSalesTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import reactor.core.publisher.Flux;

/**
 * 汽车销售 Agent —— LLM 自主决策，代码只提供工具
 *
 * 和旧架构的区别：
 * - 没有分类器、没有路由、没有分支处理器
 * - LLM 拿到 system prompt + 工具箱，自己判断该做什么
 * - 代码负责：聊天记忆 + 工具注册 + 闲聊阈值控制
 */
@Component
public class CarSalesAgent {

    private static final Logger log = LoggerFactory.getLogger(CarSalesAgent.class);

    /** 记忆窗口：保留最近 40 条消息（约 20 轮对话） */
    private static final int MEMORY_MAX_MESSAGES = 40;

    /** 闲聊阈值：连续非买车意图超过此值则自动终止 */
    private static final int IDLE_CHAT_LIMIT = 3;

    /** 闲聊终止语 */
    private static final String IDLE_TERMINATION = "买车的事随时找我，先不打扰您了～有需要再聊！";

    // 每个用户连续闲聊计数
    private final Map<String, Integer> idleCounters = new ConcurrentHashMap<>();

    private final ChatClient chatClient;
    private final ChatMemory chatMemory;

    /**
     * 汽车相关的关键词 —— 命中任一即视为"非闲聊"
     * 覆盖：品牌、车型、购车术语、门店相关
     */
    private static final Set<String> CAR_KEYWORDS = Set.of(
            "比亚迪", "特斯拉", "小鹏", "理想", "蔚来", "问界", "极氪", "领克", "长安",
            "吉利", "长城", "奇瑞", "宝马", "奔驰", "奥迪", "丰田", "本田", "大众",
            "宋", "秦", "汉", "唐", "海鸥", "海豚", "海豹", "元",
            "model", "model3", "modely", "models", "modelx",
            "p7", "g6", "g9", "l6", "l7", "l8", "l9", "m9", "et5", "et7",
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
                         @Value("${company.name}") String companyName) {
        // 内存聊天记忆，显式设置窗口大小
        ChatMemoryRepository repo = new InMemoryChatMemoryRepository();
        this.chatMemory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(repo)
                .maxMessages(MEMORY_MAX_MESSAGES)
                .build();

        // 记忆 Advisor
        MessageChatMemoryAdvisor memoryAdvisor = MessageChatMemoryAdvisor.builder(chatMemory).build();

        // RAG 检索 Advisor：每次请求前自动从知识库检索相关块注入上下文
        // topK=5 召回百科/话术/车源三类知识；阈值 0.5 以下视为不相关不注入（防幻觉）
        QuestionAnswerAdvisor ragAdvisor = QuestionAnswerAdvisor.builder(vectorStore)
                .searchRequest(SearchRequest.builder()
                        .topK(5)
                        .similarityThreshold(0.5)
                        .build())
                .build();

        // 动态生成 System Prompt（公司名来自配置文件，门店信息通过 getStoreInfo() 工具查询）
        String systemPrompt = PromptTemplates.systemPrompt(companyName);

        // ChatClient：动态 system prompt + 记忆 + RAG 检索 + 工具
        this.chatClient = ChatClient.builder(chatModel)
                .defaultSystem(systemPrompt)
                .defaultAdvisors(memoryAdvisor, ragAdvisor)
                .defaultTools(tools)
                .build();

        log.info("CarSalesAgent 初始化完成（公司：{}, 工具数：3, 记忆窗口：{}条, 闲聊阈值：{}轮, RAG：topK=5/threshold=0.5）",
                companyName, MEMORY_MAX_MESSAGES, IDLE_CHAT_LIMIT);
    }

    /**
     * 处理用户消息
     */
    public String chat(String userId, String userMessage) {
        log.info("Agent 收到: userId={}, message={}",
                userId, userMessage.substring(0, Math.min(80, userMessage.length())));

        // ---- 闲聊阈值检查 ----
        if (isIdleChat(userMessage)) {
            int count = idleCounters.merge(userId, 1, Integer::sum);
            log.info("闲聊计数: userId={}, count={}/{}", userId, count, IDLE_CHAT_LIMIT);
            if (count > IDLE_CHAT_LIMIT) {
                log.info("闲聊阈值触发: userId={}, 直接返回终止语", userId);
                return IDLE_TERMINATION;
            }
        } else {
            // 命中汽车相关关键词 → 重置计数
            idleCounters.remove(userId);
        }

        // ---- 调用 LLM ----
        String response = chatClient.prompt()
                .user(userMessage)
                .advisors(a -> a.param("chat_memory_conversation_id", userId))
                .call()
                .content();

        log.info("Agent 回复: length={}", response != null ? response.length() : 0);
        return response;
    }

    /**
     * 判断用户输入是否为"闲聊"（非买车意图）
     * 规则：转小写后逐词匹配，命中任一汽车关键词即视为非闲聊
     */
    private boolean isIdleChat(String message) {
        String lower = message.toLowerCase();
        for (String kw : CAR_KEYWORDS) {
            if (lower.contains(kw.toLowerCase())) {
                return false; // 命中 → 不是闲聊
            }
        }
        return true; // 零命中 → 是闲聊
    }

    /**
     * 处理用户消息（流式输出，SSE）
     */
    public Flux<String> chatStream(String userId, String userMessage) {
        log.info("Agent 收到(流式): userId={}, message={}",
                userId, userMessage.substring(0, Math.min(80, userMessage.length())));

        // 闲聊阈值检查（和同步版共用同一套逻辑）
        if (isIdleChat(userMessage)) {
            int count = idleCounters.merge(userId, 1, Integer::sum);
            log.info("闲聊计数(流式): userId={}, count={}/{}", userId, count, IDLE_CHAT_LIMIT);
            if (count > IDLE_CHAT_LIMIT) {
                log.info("闲聊阈值触发(流式): userId={}, 直接返回终止语", userId);
                return Flux.just(IDLE_TERMINATION);
            }
        } else {
            idleCounters.remove(userId);
        }

        return chatClient.prompt()
                .user(userMessage)
                .advisors(a -> a.param("chat_memory_conversation_id", userId))
                .stream()
                .content();
    }

    /**
     * 暴露给外部（如 Controller）用于清理会话
     */
    public void resetIdleCounter(String userId) {
        idleCounters.remove(userId);
    }
}