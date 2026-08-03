package org.example.ai.controller;

import org.example.ai.ChatService;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * macan 企微客服平替千问 API。
 *
 * <p>macan 的 {@code AIProjectServiceImpl} 通过 HTTP 调用本接口，
 * 替代原来对阿里百炼千问的直接调用。</p>
 *
 * <h3>协议</h3>
 * <pre>
 * POST /api/chat/messages
 * Content-Type: application/json
 *
 * {
 *   "messages": [
 *     {"role": "system", "content": "系统人设"},
 *     {"role": "user", "content": "历史用户消息"},
 *     {"role": "assistant", "content": "历史AI回复"},
 *     {"role": "user", "content": "当前用户消息"}
 *   ],
 *   "userId": "externalUserId"    // 可选，仅用于日志追溯
 * }
 *
 * 响应：{"reply": "AI 生成的回复文本"}
 * </pre>
 *
 * <p>无状态：macan 每次请求自带 Redis 全量历史。AI 项目侧每请求新建会话、
 * 灌入历史、生成回复后立即清空，不跨请求保留任何状态，避免不同客户串话。</p>
 *
 * @see ChatService#chatWithHistory(String, String, List)
 */
@RestController
public class MacanChatController {

    private final ChatService chatService;

    public MacanChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    /**
     * 平替千问 chat completions：入参与 OpenAI messages 字段一致，出参 {"reply": "..."}。
     *
     * <p>macan 传来 messages 的最后一条 user 作为当前客户消息，
     * 之前的 user/assistant 作为历史上下文灌入一次性会话。
     * system 消息被忽略（AI 项目使用自有的 system prompt + RAG 上下文）。</p>
     */
    @PostMapping("/api/chat/messages")
    public Map<String, String> chatForMacan(@RequestBody MacanChatRequest request) {
        List<MacanMessage> messages = request.messages() != null ? request.messages() : List.of();

        // 定位最后一条 user 消息（当前客户消息）
        int lastUserIdx = -1;
        for (int i = 0; i < messages.size(); i++) {
            if ("user".equals(messages.get(i).role())) {
                lastUserIdx = i;
            }
        }
        if (lastUserIdx < 0 || messages.get(lastUserIdx).content() == null
                || messages.get(lastUserIdx).content().isBlank()) {
            return Map.of("reply", "");
        }
        String userMessage = messages.get(lastUserIdx).content();

        // 历史 = 当前消息之前的 user/assistant（system 过滤掉）
        List<org.springframework.ai.chat.messages.Message> history = new ArrayList<>();
        for (int i = 0; i < lastUserIdx; i++) {
            MacanMessage m = messages.get(i);
            if (m.content() == null || m.content().isBlank()) continue;
            if ("user".equals(m.role())) {
                history.add(new org.springframework.ai.chat.messages.UserMessage(m.content()));
            } else if ("assistant".equals(m.role())) {
                history.add(new org.springframework.ai.chat.messages.AssistantMessage(m.content()));
            }
        }

        // 一次性会话 ID：userId 仅用于日志，拼接 UUID 保证并发隔离与无状态
        String conversationId = (request.userId() != null && !request.userId().isBlank()
                ? request.userId() : "macan") + "-" + UUID.randomUUID();

        String reply = chatService.chatWithHistory(conversationId, userMessage, history);
        return Map.of("reply", reply != null ? reply : "");
    }

    // ==================== DTO ====================

    /** macan 请求体：与 OpenAI chat completions 的 messages 字段一致。 */
    public record MacanChatRequest(List<MacanMessage> messages, String userId) {}

    /** macan 消息结构（role: "system" | "user" | "assistant"）。 */
    public record MacanMessage(String role, String content) {}
}