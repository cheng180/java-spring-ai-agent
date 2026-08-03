package org.example.ai.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.example.ai.ChatService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.Map;

/**
 * 智能客服 HTTP 接口 —— 极薄适配层，所有逻辑委托给 {@link ChatService}。
 *
 * <p>这是 AI 项目对外暴露的全部对话端点。macan 企微客服平替千问的接口
 * 见 {@link MacanChatController}。</p>
 */
@RestController
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    // ==================== GET 接口（浏览器快速测试） ====================
    @GetMapping("/chat")
    public String chat(@RequestParam(defaultValue = "你好") String message,
                       HttpServletRequest request) {
        return chatService.chat("web-user", message, clientIp(request));
    }

    // ==================== POST 接口（前端调用） ====================
    @PostMapping("/api/chat")
    public Map<String, Object> chat(@RequestBody ChatRequest req,
                                    HttpServletRequest httpRequest) {
        String reply = chatService.chat(
                req.userId() != null ? req.userId() : "web-user",
                req.message() != null ? req.message() : "你好",
                clientIp(httpRequest));
        return Map.of("success", true, "data", Map.of("reply", reply));
    }

    // ==================== 流式接口（SSE，逐字输出） ====================
    @PostMapping(value = "/api/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chatStream(@RequestBody ChatRequest req,
                                   HttpServletRequest httpRequest) {
        return chatService.chatStream(
                req.userId() != null ? req.userId() : "web-user",
                req.message() != null ? req.message() : "你好",
                clientIp(httpRequest))
                .map(chunk -> "data:" + chunk + "\n\n")
                .concatWithValues("data:[DONE]\n\n");
    }

    @GetMapping("/api/health")
    public Map<String, String> health() {
        return Map.of("status", "UP", "service", "卖好车智能汽车销售客服");
    }

    /** 提取客户端真实 IP。X-Forwarded-For 优先，getRemoteAddr 兜底。 */
    private String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    // ==================== DTO ====================
    public record ChatRequest(String message, String userId) {}
}