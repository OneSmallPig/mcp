package org.yubang.util.mcpdemo.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.yubang.util.mcpdemo.service.SseEmitterService;
import org.yubang.util.mcpdemo.service.LlmService;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SSE控制器，用于处理MCP协议的SSE连接和事件
 */
@RestController
@RequestMapping("/api")
public class SseController {
    private static final Logger log = LoggerFactory.getLogger(SseController.class);
    
    private final SseEmitterService sseEmitterService;
    private final LlmService llmService;
    
    public SseController(SseEmitterService sseEmitterService, LlmService llmService) {
        this.sseEmitterService = sseEmitterService;
        this.llmService = llmService;
    }
    
    /**
     * SSE连接端点
     * 用于建立客户端与服务器之间的SSE长连接
     * 
     * @return SseEmitter对象
     */
    @GetMapping(value = "/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter connect() {
        String clientId = UUID.randomUUID().toString();
        log.info("建立新的SSE连接: {}", clientId);
        
        SseEmitter emitter = sseEmitterService.createEmitter(clientId);
        
        // 发送初始连接成功事件
        try {
            sseEmitterService.sendEvent(clientId, "connected", Map.of(
                "client_id", clientId,
                "message", "连接已建立"
            ));
        } catch (IOException e) {
            log.error("发送连接事件失败", e);
            sseEmitterService.removeEmitter(clientId);
        }
        
        return emitter;
    }
    
    /**
     * 接收来自客户端的LLM请求
     * 将请求转发到LLM服务，由LLM判断是否需要调用工具
     */
    @PostMapping("/llm/chat")
    public Map<String, Object> handleLlmChat(@RequestBody Map<String, Object> request) {
        log.info("收到LLM聊天请求: {}", request);
        
        String clientId = (String) request.getOrDefault("client_id", "");
        if (clientId.isEmpty() || !sseEmitterService.hasEmitter(clientId)) {
            return Map.of("error", "无效的客户端ID");
        }
        
        // 异步处理LLM请求
        llmService.processLlmRequest(request, clientId, sseEmitterService);
        
        // 返回接收成功的响应
        return Map.of(
            "success", true,
            "message", "请求已接收，结果将通过SSE发送",
            "client_id", clientId
        );
    }
    
    /**
     * 接收来自大模型的聊天请求
     * 处理并通过SSE发送给对应的客户端
     * 此端点通常由LLM服务或MCP中间件调用
     */
    @PostMapping("/tools/execute")
    public Map<String, Object> handleChat(@RequestBody Map<String, Object> request) {
        log.info("收到聊天响应转发请求: {}", request);
        
        String clientId = (String) request.getOrDefault("client_id", "");
        if (clientId.isEmpty() || !sseEmitterService.hasEmitter(clientId)) {
            return Map.of("error", "无效的客户端ID");
        }
        
        // 处理文本内容
        if (request.containsKey("content")) {
            try {
                sseEmitterService.sendEvent(clientId, "text_chunk", Map.of(
                    "content", request.get("content")
                ));
            } catch (IOException e) {
                log.error("发送文本事件失败", e);
                return Map.of("error", "发送事件失败");
            }
        }
        
        // 处理工具调用
        if (request.containsKey("tool_calls") && request.get("tool_calls") instanceof Iterable) {
            Iterable<?> toolCalls = (Iterable<?>) request.get("tool_calls");
            for (Object toolCall : toolCalls) {
                if (toolCall instanceof Map) {
                    try {
                        Map<?, ?> toolCallMap = (Map<?, ?>) toolCall;
                        sseEmitterService.sendEvent(clientId, "tool_call", toolCallMap);
                    } catch (IOException e) {
                        log.error("发送工具调用事件失败", e);
                    }
                }
            }
        }
        
        return Map.of("success", true);
    }
    
    /**
     * 接收工具调用结果
     * 将结果转发给大模型进行后续处理
     */
    @PostMapping("/tool-result")
    public Map<String, Object> handleToolResult(@RequestBody Map<String, Object> result) {
        log.info("收到工具调用结果: {}", result);
        
        String clientId = (String) result.getOrDefault("client_id", "");
        if (clientId.isEmpty() || !sseEmitterService.hasEmitter(clientId)) {
            return Map.of("error", "无效的客户端ID");
        }
        
        // 发送工具结果事件
        try {
            sseEmitterService.sendEvent(clientId, "tool_result", result);
            
            // 将工具结果发送给LLM服务继续处理
            llmService.processToolResult(result, clientId, sseEmitterService);
        } catch (IOException e) {
            log.error("发送工具结果事件失败", e);
            return Map.of("error", "发送工具结果失败");
        }
        
        return Map.of("success", true);
    }
    
    /**
     * 结束聊天会话
     */
    @PostMapping("/chat/complete")
    public Map<String, Object> completeChat(@RequestBody Map<String, Object> request) {
        String clientId = (String) request.getOrDefault("client_id", "");
        if (clientId.isEmpty() || !sseEmitterService.hasEmitter(clientId)) {
            return Map.of("error", "无效的客户端ID");
        }
        
        try {
            sseEmitterService.sendEvent(clientId, "finished", Map.of(
                "message", "聊天会话已完成"
            ));
        } catch (IOException e) {
            log.error("发送完成事件失败", e);
            return Map.of("error", "发送完成事件失败");
        }
        
        return Map.of("success", true);
    }
} 