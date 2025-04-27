package org.yubang.util.mcpdemo.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.yubang.util.mcpdemo.service.SseEmitterService;
import org.yubang.util.mcpdemo.service.ToolExecutionService;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.HashMap;
import jakarta.servlet.http.HttpServletResponse;

/**
 * SSE控制器，用于处理MCP协议的SSE连接和事件
 */
@RestController
@RequestMapping("/api")
public class SseController {
    private static final Logger log = LoggerFactory.getLogger(SseController.class);
    
    private final SseEmitterService sseEmitterService;
    
    @Autowired
    private ToolExecutionService toolExecutionService;

    public SseController(SseEmitterService sseEmitterService) {
        this.sseEmitterService = sseEmitterService;
    }
    
    /**
     * SSE连接端点
     * 用于建立客户端与服务器之间的SSE长连接
     * 
     * @return SseEmitter对象
     */
    @GetMapping(value = "/sse", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter connect(HttpServletResponse response) {
        // 添加额外的响应头，确保使用UTF-8编码
        response.setHeader("Content-Type", "text/event-stream;charset=UTF-8");
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("Connection", "keep-alive");
        response.setCharacterEncoding("UTF-8");
        
        String clientId = UUID.randomUUID().toString();
        log.info("建立新的SSE连接: {}", clientId);
        
        SseEmitter emitter = sseEmitterService.createEmitter(clientId);
        
        // 发送初始连接成功事件
        Map<String, Object> connectionData = Map.of(
            "client_id", clientId,
            "message", "连接已建立",
            "server_time", System.currentTimeMillis()
        );
        
        // 使用安全发送方法
        if (!sseEmitterService.sendEventSafely(clientId, "connected", connectionData)) {
            log.error("无法发送初始连接事件，移除连接");
            sseEmitterService.removeEmitter(clientId);
            return null; // 返回null会导致Spring自动处理异常
        }
        
        return emitter;
    }
    
    /**
     * 接收来自大模型的聊天请求
     * 处理并通过SSE发送给对应的客户端
     * 此端点通常由LLM服务或MCP中间件调用
     */
    @PostMapping("/tools/execute")
    public Map<String, Object> handleChat(@RequestBody Map<String, Object> request) {
        log.info("收到工具执行请求: {}", request);
        
        String clientId = (String) request.getOrDefault("client_id", "");
        if (clientId.isEmpty() || !sseEmitterService.hasEmitter(clientId)) {
            log.error("工具执行请求使用了无效的客户端ID: {}", clientId);
            return Map.of("error", "无效的客户端ID", "status", "error");
        }
        
        // 检查连接是否存在，并发送ping事件确认连接活跃
        if (!pingClient(clientId)) {
            log.error("客户端 {} 连接状态异常，无法发送工具调用", clientId);
            return Map.of("error", "客户端连接状态异常", "status", "error");
        }
        
        // 直接处理客户端发送的工具调用格式
        if (request.containsKey("tool_name") && request.containsKey("arguments")) {
            try {
                String toolName = (String) request.get("tool_name");
                Map<String, Object> arguments = (Map<String, Object>) request.get("arguments");
                
                // 生成工具调用ID
                String toolCallId = UUID.randomUUID().toString();
                
                log.info("准备执行工具调用: {}, id: {}", toolName, toolCallId);
                
                // 1. 向客户端发送工具调用状态事件
                Map<String, Object> startEvent = new HashMap<>();
                startEvent.put("message", "开始执行工具: " + toolName);
                startEvent.put("status", "processing");
                startEvent.put("tool_call_id", toolCallId);
                startEvent.put("tool_name", toolName);
                startEvent.put("arguments", arguments);
                
                if (!sseEmitterService.sendEventSafely(clientId, "tool_status", startEvent)) {
                    log.error("无法发送工具状态事件，客户端可能已断开连接");
                    return Map.of("error", "客户端连接已断开", "status", "error");
                }
                
                // 2. 执行工具调用
                String toolResult = executeToolFunction(toolName, arguments);
                log.info("工具执行完成, 结果: {}", toolResult);
                
                // 3. 发送工具执行结果给客户端
                Map<String, Object> resultEvent = new HashMap<>();
                resultEvent.put("tool_call_id", toolCallId);
                resultEvent.put("result", toolResult);
                resultEvent.put("status", "completed");
                
                sseEmitterService.sendEvent(clientId, "tool_result", resultEvent);
                
                // 4. 发送完成事件
                sseEmitterService.sendEvent(clientId, "tool_complete", Map.of(
                    "message", "工具执行完成",
                    "tool_call_id", toolCallId
                ));
                
                // 返回工具执行结果
                return Map.of("result", toolResult);
            } catch (IOException e) {
                log.error("发送工具调用事件失败", e);
                return Map.of("error", "发送工具调用事件失败: " + e.getMessage());
            } catch (Exception e) {
                log.error("处理工具调用请求失败", e);
                return Map.of("error", "处理工具调用请求失败: " + e.getMessage(), "status", "error");
            }
        }
        
        return Map.of("success", true);
    }
    
    /**
     * 向客户端发送ping事件确认连接状态
     * 
     * @param clientId 客户端ID
     * @return 连接是否正常
     */
    private boolean pingClient(String clientId) {
        if (!sseEmitterService.hasEmitter(clientId)) {
            return false;
        }
        
        Map<String, Object> pingData = Map.of(
            "type", "ping",
            "timestamp", System.currentTimeMillis()
        );
        
        return sseEmitterService.sendEventSafely(clientId, "ping", pingData);
    }
    
    
    
    /**
     * 执行工具函数
     * 
     * @param toolName 工具名称
     * @param arguments 工具参数
     * @return 工具执行结果
     */
    private String executeToolFunction(String toolName, Map<String, Object> arguments) {
        log.info("执行工具函数: {}, 参数: {}", toolName, arguments);
        
        try {
            // 使用注入的ToolExecutionService来执行工具
            return toolExecutionService.executeToolByName(toolName, arguments);
        } catch (Exception e) {
            log.error("执行工具函数失败", e);
            return "工具执行错误: " + e.getMessage();
        }
    }
    
    /**
     * 结束聊天会话
     */
    @PostMapping("/chat/complete")
    public Map<String, Object> completeChat(@RequestBody Map<String, Object> request) {
        String clientId = (String) request.getOrDefault("client_id", "");
        if (clientId.isEmpty() || !sseEmitterService.hasEmitter(clientId)) {
            return Map.of("error", "无效的客户端ID", "status", "error");
        }
        
        Map<String, Object> finishData = Map.of(
            "message", "聊天会话已完成",
            "timestamp", System.currentTimeMillis()
        );
        
        if (!sseEmitterService.sendEventSafely(clientId, "finished", finishData)) {
            log.warn("发送完成事件失败，客户端可能已断开连接");
            return Map.of("error", "发送完成事件失败", "status", "error");
        }
        
        return Map.of("success", true, "status", "completed");
    }
} 