package org.example.ai.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.example.ai.agent.CarSalesAgent;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.Map;

@RestController
public class ChatController {

    private final CarSalesAgent agent;

    public ChatController(CarSalesAgent agent) {
        this.agent = agent;
    }

    // ==================== GET 接口（浏览器快速测试） ====================
    @GetMapping("/chat")
    public String chat(@RequestParam(defaultValue = "你好") String message,
                       HttpServletRequest request) {
        return agent.chat("web-user", message, clientIp(request));
    }

    // ==================== POST 接口（前端调用） ====================
    @PostMapping("/api/chat")
    public Map<String, Object> chat(@RequestBody ChatRequest request,
                                    HttpServletRequest httpRequest) {
        String reply = agent.chat(
                request.userId() != null ? request.userId() : "web-user",
                request.message() != null ? request.message() : "你好",
                clientIp(httpRequest));
        return Map.of("success", true, "data", Map.of("reply", reply));
    }

    // ==================== 流式接口（SSE，逐字输出） ====================
    @PostMapping(value = "/api/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chatStream(@RequestBody ChatRequest request,
                                   HttpServletRequest httpRequest) {
        return agent.chatStream(
                request.userId() != null ? request.userId() : "web-user",
                request.message() != null ? request.message() : "你好",
                clientIp(httpRequest))
                .map(chunk -> "data:" + chunk + "\n\n")
                .concatWithValues("data:[DONE]\n\n");
    }

    @GetMapping("/api/health")
    public Map<String, String> health() {
        return Map.of("status", "UP", "service", "卖好车智能汽车销售客服");
    }

    /** 提取客户端真实 IP（#13 ticket）。X-Forwarded-For 优先，getRemoteAddr 兜底。 */
    private String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim(); // 取最左边（原始客户端）
        }
        return request.getRemoteAddr();
    }

    // ==================== DTO ====================
    public record ChatRequest(String message, String userId) {}
}