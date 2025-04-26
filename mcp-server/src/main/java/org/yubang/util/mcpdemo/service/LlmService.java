package org.yubang.util.mcpdemo.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * LLM服务
 * 负责与大模型交互，处理聊天请求和工具调用
 */
@Service
public class LlmService {
    private static final Logger log = LoggerFactory.getLogger(LlmService.class);
    private final RestTemplate restTemplate;
    private final Gson gson;
    private final ExecutorService executorService;
    private final String llmApiUrl; // 大模型API URL
    private final String llmApiKey; // 大模型API Key

    public LlmService() {
        this.restTemplate = new RestTemplate();
        this.gson = new Gson();
        this.executorService = Executors.newCachedThreadPool();
        
        // 从环境变量或配置中获取LLM API配置
        this.llmApiUrl = System.getenv("LLM_API_URL");
        this.llmApiKey = System.getenv("LLM_API_KEY");
        
        log.info("LLM服务初始化完成，API URL: {}", llmApiUrl != null ? llmApiUrl : "未设置");
    }

    /**
     * 处理LLM请求
     * 异步调用大模型API，并将结果通过SSE发送给客户端
     *
     * @param request 请求内容
     * @param clientId 客户端ID
     * @param sseEmitterService SSE服务
     */
    public void processLlmRequest(Map<String, Object> request, String clientId, SseEmitterService sseEmitterService) {
        executorService.submit(() -> {
            try {
                log.info("开始处理LLM请求：clientId={}", clientId);
                
                // 如果有配置外部LLM API，则调用外部API
                if (llmApiUrl != null && !llmApiUrl.isEmpty()) {
                    // 调用外部大模型API
                    callExternalLlmApi(request, clientId, sseEmitterService);
                } else {
                    // 使用模拟响应（实际项目中应替换为真实的大模型集成）
                    simulateLlmResponse(request, clientId, sseEmitterService);
                }
            } catch (Exception e) {
                log.error("处理LLM请求失败", e);
                try {
                    // 发送错误事件
                    sseEmitterService.sendEvent(clientId, "error", Map.of(
                        "message", "处理LLM请求失败: " + e.getMessage()
                    ));
                } catch (IOException ex) {
                    log.error("发送错误事件失败", ex);
                }
            }
        });
    }

    /**
     * 调用外部LLM API
     *
     * @param request 请求内容
     * @param clientId 客户端ID
     * @param sseEmitterService SSE服务
     */
    private void callExternalLlmApi(Map<String, Object> request, String clientId, SseEmitterService sseEmitterService) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            if (llmApiKey != null && !llmApiKey.isEmpty()) {
                headers.set("Authorization", "Bearer " + llmApiKey);
            }

            // 修改请求以包含客户端ID，便于关联响应
            Map<String, Object> modifiedRequest = new HashMap<>(request);
            modifiedRequest.put("client_id", clientId);

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(modifiedRequest, headers);
            
            log.info("调用外部LLM API: {}", llmApiUrl);
            ResponseEntity<String> response = restTemplate.postForEntity(llmApiUrl, entity, String.class);
            
            if (response.getStatusCode().is2xxSuccessful()) {
                log.info("LLM API调用成功");
                // 处理LLM响应，这通常是流式响应，需要根据实际情况适配
                handleLlmApiResponse(response.getBody(), clientId, sseEmitterService);
            } else {
                log.error("LLM API调用失败: {}", response.getStatusCode());
                sseEmitterService.sendEvent(clientId, "error", Map.of(
                    "message", "LLM API调用失败: " + response.getStatusCode()
                ));
            }
        } catch (Exception e) {
            log.error("调用外部LLM API时发生错误", e);
            try {
                sseEmitterService.sendEvent(clientId, "error", Map.of(
                    "message", "调用LLM API错误: " + e.getMessage()
                ));
            } catch (IOException ex) {
                log.error("发送错误事件失败", ex);
            }
        }
    }

    /**
     * 处理LLM API响应
     *
     * @param responseBody 响应内容
     * @param clientId 客户端ID
     * @param sseEmitterService SSE服务
     */
    private void handleLlmApiResponse(String responseBody, String clientId, SseEmitterService sseEmitterService) {
        try {
            // 根据实际LLM API响应格式解析
            // 这里仅是示例，需要根据实际使用的LLM API进行适配
            JsonObject response = gson.fromJson(responseBody, JsonObject.class);
            
            // 如果响应包含文本内容
            if (response.has("choices") && response.getAsJsonArray("choices").size() > 0) {
                JsonObject choice = response.getAsJsonArray("choices").get(0).getAsJsonObject();
                if (choice.has("message") && choice.getAsJsonObject("message").has("content")) {
                    String content = choice.getAsJsonObject("message").get("content").getAsString();
                    // 发送文本内容给客户端
                    sseEmitterService.sendEvent(clientId, "text_chunk", Map.of("content", content));
                }
                
                // 如果响应包含工具调用
                if (choice.has("tool_calls")) {
                    choice.getAsJsonArray("tool_calls").forEach(toolCallElement -> {
                        JsonObject toolCall = toolCallElement.getAsJsonObject();
                        // 发送工具调用事件
                        try {
                            sseEmitterService.sendEvent(clientId, "tool_call", gson.fromJson(toolCall, Map.class));
                        } catch (IOException e) {
                            log.error("发送工具调用事件失败", e);
                        }
                    });
                }
            }
            
            // 发送完成事件
            sseEmitterService.sendEvent(clientId, "finished", Map.of("message", "LLM处理完成"));
            
        } catch (Exception e) {
            log.error("处理LLM API响应失败", e);
            try {
                sseEmitterService.sendEvent(clientId, "error", Map.of(
                    "message", "处理LLM响应错误: " + e.getMessage()
                ));
            } catch (IOException ex) {
                log.error("发送错误事件失败", ex);
            }
        }
    }

    /**
     * 模拟LLM响应（仅用于测试）
     *
     * @param request 请求内容
     * @param clientId 客户端ID
     * @param sseEmitterService SSE服务
     */
    private void simulateLlmResponse(Map<String, Object> request, String clientId, SseEmitterService sseEmitterService) {
        try {
            log.info("使用模拟LLM响应");
            
            // 获取用户消息
            String userMessage = extractUserMessage(request);
            
            // 根据用户消息决定是返回文本还是调用工具
            boolean shouldCallTool = userMessage.toLowerCase().contains("天气") ||
                                     userMessage.toLowerCase().contains("查询") ||
                                     userMessage.toLowerCase().contains("搜索");
            
            if (shouldCallTool) {
                // 模拟发送工具调用事件
                Map<String, Object> toolCall = new HashMap<>();
                toolCall.put("id", "call_" + System.currentTimeMillis());
                toolCall.put("function", userMessage.toLowerCase().contains("天气") ? "get_weather" : "search");
                
                Map<String, Object> arguments = new HashMap<>();
                if (userMessage.toLowerCase().contains("天气")) {
                    arguments.put("city", extractCity(userMessage));
                } else {
                    arguments.put("query", userMessage.replaceAll("(?i)查询|搜索", "").trim());
                }
                toolCall.put("arguments", arguments);
                
                // 发送工具调用事件
                sseEmitterService.sendEvent(clientId, "tool_call", toolCall);
                
                // 模拟短暂延迟
                Thread.sleep(1000);
                
                // 不直接发送完成事件，等待工具调用结果
            } else {
                // 模拟文本响应
                String[] chunks = {
                    "您好！",
                    "我是MCP示例助手。",
                    "我可以帮您查询天气、搜索信息等。"
                };
                
                // 逐个发送文本块，模拟流式响应
                for (String chunk : chunks) {
                    sseEmitterService.sendEvent(clientId, "text_chunk", Map.of("content", chunk));
                    Thread.sleep(300); // 模拟延迟
                }
                
                // 发送完成事件
                sseEmitterService.sendEvent(clientId, "finished", Map.of("message", "对话完成"));
            }
            
        } catch (Exception e) {
            log.error("模拟LLM响应失败", e);
            try {
                sseEmitterService.sendEvent(clientId, "error", Map.of(
                    "message", "模拟LLM响应错误: " + e.getMessage()
                ));
            } catch (IOException ex) {
                log.error("发送错误事件失败", ex);
            }
        }
    }

    /**
     * 处理工具调用结果
     * 将结果发送回大模型，获取后续回复
     *
     * @param result 工具调用结果
     * @param clientId 客户端ID
     * @param sseEmitterService SSE服务
     */
    public void processToolResult(Map<String, Object> result, String clientId, SseEmitterService sseEmitterService) {
        executorService.submit(() -> {
            try {
                log.info("处理工具调用结果: clientId={}, toolFunction={}", 
                         clientId, result.getOrDefault("function", "unknown"));
                
                // 如果有外部LLM API，则发送工具结果给LLM
                if (llmApiUrl != null && !llmApiUrl.isEmpty()) {
                    // 调用LLM API处理工具结果
                    sendToolResultToLlmApi(result, clientId, sseEmitterService);
                } else {
                    // 模拟LLM对工具结果的处理
                    simulateToolResultProcessing(result, clientId, sseEmitterService);
                }
            } catch (Exception e) {
                log.error("处理工具调用结果失败", e);
                try {
                    sseEmitterService.sendEvent(clientId, "error", Map.of(
                        "message", "处理工具结果失败: " + e.getMessage()
                    ));
                } catch (IOException ex) {
                    log.error("发送错误事件失败", ex);
                }
            }
        });
    }

    /**
     * 将工具结果发送给LLM API
     *
     * @param result 工具调用结果
     * @param clientId 客户端ID
     * @param sseEmitterService SSE服务
     */
    private void sendToolResultToLlmApi(Map<String, Object> result, String clientId, SseEmitterService sseEmitterService) {
        // 实现与特定LLM API的集成
        // 此处需要根据实际使用的LLM API进行适配
        log.info("将工具结果发送给LLM API的功能尚未实现");
        
        // 模拟完成事件
        try {
            sseEmitterService.sendEvent(clientId, "finished", Map.of("message", "工具调用处理完成"));
        } catch (IOException e) {
            log.error("发送完成事件失败", e);
        }
    }

    /**
     * 模拟LLM对工具结果的处理（仅用于测试）
     *
     * @param result 工具调用结果
     * @param clientId 客户端ID
     * @param sseEmitterService SSE服务
     */
    private void simulateToolResultProcessing(Map<String, Object> result, String clientId, SseEmitterService sseEmitterService) {
        try {
            // 获取工具函数名称和结果
            String function = (String) result.getOrDefault("function", "unknown");
            String toolResult = (String) result.getOrDefault("result", "");
            
            // 根据工具函数生成不同的响应
            String response;
            if (function.equals("get_weather")) {
                response = "根据查询结果，" + toolResult;
            } else if (function.equals("search")) {
                response = "我找到了以下信息：\n" + toolResult;
            } else {
                response = "工具 " + function + " 返回结果：" + toolResult;
            }
            
            // 模拟短暂延迟
            Thread.sleep(500);
            
            // 发送回复文本
            sseEmitterService.sendEvent(clientId, "text_chunk", Map.of("content", response));
            
            // 发送完成事件
            Thread.sleep(200);
            sseEmitterService.sendEvent(clientId, "finished", Map.of("message", "工具调用处理完成"));
            
        } catch (Exception e) {
            log.error("模拟工具结果处理失败", e);
            try {
                sseEmitterService.sendEvent(clientId, "error", Map.of(
                    "message", "处理工具结果错误: " + e.getMessage()
                ));
            } catch (IOException ex) {
                log.error("发送错误事件失败", ex);
            }
        }
    }

    /**
     * 从请求中提取用户消息
     *
     * @param request 请求内容
     * @return 用户消息
     */
    private String extractUserMessage(Map<String, Object> request) {
        try {
            if (request.containsKey("messages") && request.get("messages") instanceof Iterable) {
                Iterable<?> messages = (Iterable<?>) request.get("messages");
                for (Object msg : messages) {
                    if (msg instanceof Map) {
                        Map<?, ?> message = (Map<?, ?>) msg;
                        if ("user".equals(message.get("role"))) {
                            return (String) message.get("content");
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("提取用户消息失败", e);
        }
        return ""; // 默认空消息
    }

    /**
     * 从消息中提取城市名称（简单实现）
     *
     * @param message 消息内容
     * @return 城市名称
     */
    private String extractCity(String message) {
        // 简单的城市提取逻辑，实际应用中可能需要更复杂的NLP
        String[] cities = {"北京", "上海", "广州", "深圳", "杭州", "南京", "成都", "重庆", "武汉", "西安"};
        for (String city : cities) {
            if (message.contains(city)) {
                return city;
            }
        }
        return "北京"; // 默认城市
    }
} 