package org.example.ai;

import java.util.List;

/**
 * 智能客服对话服务 —— AI 项目对外唯一契约。
 *
 * <p>所有调用方（Web 前端、macan 企微客服、其他系统）都通过此接口消费 AI 对话能力，
 * 不感知 RAG 检索、实体识别、路由、LLM 工具调用等内部实现。</p>
 *
 * <h3>深度说明</h3>
 * <ul>
 *   <li><b>Interface</b>：3 个方法，参数都是基础类型 + 标准 Message</li>
 *   <li><b>Implementation</b>：两阶段混合检索（BM25 + BGE-M3）、EntityResolver 别名匹配、
 *       VagueQueryRouter 三层路由、ChatMemory 会话管理、LLM Tool Calling、
 *       热度追踪、防幻觉 prompt 工程等全部隐藏</li>
 * </ul>
 *
 * @see org.example.ai.impl.CarSalesAgent
 */
public interface ChatService {

    /**
     * 对话回复（Web 前端 / 通用调用）。
     *
     * @param userId      用户标识（会话隔离，用于 ChatMemory）
     * @param userMessage 用户当前消息
     * @param userIp      客户端 IP（门店定位用，可传 "unknown"）
     * @return AI 生成的回复文本
     */
    String chat(String userId, String userMessage, String userIp);

    /**
     * 流式对话回复（SSE）。
     *
     * @param userId      用户标识
     * @param userMessage 用户当前消息
     * @param userIp      客户端 IP
     * @return 逐字流式输出的回复
     */
    reactor.core.publisher.Flux<String> chatStream(String userId, String userMessage, String userIp);

    /**
     * 无状态对话回复 —— macan 企微客服平替千问 API 专用。
     *
     * <p>macan 每次请求都自带 Redis 全量历史（system + user/assistant 交替 + 当前 user），
     * AI 项目不跨请求保存会话状态。每次调用：灌入历史 → 生成回复 → 清空会话。</p>
     *
     * @param conversationId 一次性会话 ID（每请求唯一，避免并发串话）
     * @param userMessage    当前客户消息文本
     * @param history        macan Redis 历史（user/assistant 交替，不含当前消息；system 已被 macan 侧过滤）
     * @return AI 生成的回复文本
     */
    String chatWithHistory(String conversationId, String userMessage,
                           List<org.springframework.ai.chat.messages.Message> history);
}