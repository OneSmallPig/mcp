package com.example.mcp.client.service;

import com.example.mcp.client.config.McpClientConfig;
import com.example.mcp.client.model.ChatRequest;
import com.example.mcp.client.model.Message;
import com.example.mcp.client.model.Tool;
import com.example.mcp.client.model.ToolCall;
import com.example.mcp.client.sse.EventSource;
import com.example.mcp.client.sse.EventSourceListener;
import com.example.mcp.client.sse.EventSources;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.Map;

/**
 * MCP SSE服务实现
 */
public class McpSseServiceImpl implements McpSseService {
    private static final Logger log = LoggerFactory.getLogger(McpSseServiceImpl.class);
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    
    private final OkHttpClient httpClient;
    private final Gson gson;
    private final McpClientConfig config;
    private EventSource currentEventSource;
    
    public McpSseServiceImpl() {
        this.config = McpClientConfig.getInstance();
        
        // 创建支持长连接的HTTP客户端
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(config.getTimeout(), TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.SECONDS) // 无限读取超时，用于SSE长连接
                .writeTimeout(config.getTimeout(), TimeUnit.SECONDS)
                .build();
        
        this.gson = new Gson();
        
        log.info("MCP SSE服务初始化完成");
        log.info("SSE服务器URL: {}", config.getSseServerUrl());
        log.info("SSE服务器路径: {}", config.getSseServerPath());
    }
    
    @Override
    public void sendPrompt(String prompt, SseEventListener listener) throws IOException {
        List<Message> messages = new ArrayList<>();
        // 添加系统提示，使大模型知道需要调用工具
        messages.add(new Message("system", "You are a helpful assistant. When user requests a tool function, always try to call the available tools to satisfy their request."));
        messages.add(new Message("user", prompt));
        sendPrompt(messages, prompt, listener);
    }
    
    @Override
    public void sendPrompt(List<Message> history, String prompt, SseEventListener listener) throws IOException {
        // 创建SSE连接前关闭现有连接
        if (currentEventSource != null) {
            currentEventSource.cancel();
        }
        
        // 构建完整URL
        String url = config.getSseServerUrl();
        if (!url.endsWith("/")) {
            url += "/";
        }
        url += config.getSseServerPath();
        
        log.info("创建SSE连接到: {}", url);
        
        // 创建请求体
        JsonObject requestBody = createChatRequestJson(history, prompt);
        
        // 创建请求
        Request request = new Request.Builder()
                .url(url)
                .post(RequestBody.create(requestBody.toString(), JSON))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .build();
        
        // 创建SSE连接
        currentEventSource = EventSources.createFactory(httpClient)
                .newEventSource(request, new EventSourceListener() {
                    @Override
                    public void onOpen(EventSource eventSource, Response response) {
                        log.info("SSE连接已打开");
                    }
                    
                    @Override
                    public void onEvent(EventSource eventSource, String id, String type, String data) {
                        handleSseEvent(id, type, data, listener);
                    }
                    
                    @Override
                    public void onClosed(EventSource eventSource) {
                        log.info("SSE连接已关闭");
                        listener.onComplete();
                    }
                    
                    @Override
                    public void onFailure(EventSource eventSource, Throwable t, Response response) {
                        log.error("SSE连接失败", t);
                        listener.onError(t);
                    }
                });
    }
    
    /**
     * 处理SSE事件
     */
    private void handleSseEvent(String id, String type, String data, SseEventListener listener) {
        try {
            log.debug("收到SSE事件: type={}, id={}, data={}", type, id, data);
            
            JsonObject jsonData = gson.fromJson(data, JsonObject.class);
            
            switch (type) {
                case "text_chunk":
                    String content = jsonData.get("content").getAsString();
                    log.debug("文本片段: {}", content);
                    listener.onTextChunk(content);
                    break;
                case "tool_call":
                    ToolCall toolCall = parseToolCall(jsonData);
                    log.info("工具调用: {} {}", toolCall.getFunction(), toolCall.getArguments());
                    listener.onToolCall(toolCall);
                    // 执行工具并发送结果
                    String result = executeToolCall(toolCall);
                    sendToolResult(id, toolCall.getId(), result);
                    break;
                case "tool_result":
                    String function = jsonData.get("function").getAsString();
                    String resultData = jsonData.get("result").getAsString();
                    log.info("工具结果: {} = {}", function, resultData);
                    listener.onToolResult(function, resultData);
                    break;
                case "finished":
                    log.info("交互完成");
                    listener.onComplete();
                    break;
                case "error":
                    String message = jsonData.get("message").getAsString();
                    log.error("错误: {}", message);
                    listener.onError(new Exception(message));
                    break;
                default:
                    log.warn("未知事件类型: {}", type);
                    break;
            }
        } catch (Exception e) {
            log.error("处理SSE事件失败", e);
            listener.onError(e);
        }
    }
    
    /**
     * 解析工具调用
     */
    private ToolCall parseToolCall(JsonObject jsonData) {
        ToolCall toolCall = new ToolCall();
        toolCall.setId(jsonData.has("id") ? jsonData.get("id").getAsString() : UUID.randomUUID().toString());
        toolCall.setFunction(jsonData.get("function").getAsString());
        
        if (jsonData.has("arguments") && !jsonData.get("arguments").isJsonNull()) {
            // 将JSON转换为Map<String, Object>
            @SuppressWarnings("unchecked")
            Map<String, Object> arguments = gson.fromJson(jsonData.get("arguments"), Map.class);
            toolCall.setArguments(arguments);
        }
        
        return toolCall;
    }
    
    /**
     * 执行工具调用
     */
    private String executeToolCall(ToolCall toolCall) {
        try {
            log.info("执行工具: {}", toolCall.getFunction());
            
            // 查找匹配的工具
            Tool matchingTool = null;
            for (Tool tool : config.getTools()) {
                if (tool.getName().equals(toolCall.getFunction())) {
                    matchingTool = tool;
                    break;
                }
            }
            
            if (matchingTool == null) {
                String error = "工具不存在: " + toolCall.getFunction();
                log.error(error);
                return error;
            }
            
            // 这里可以集成现有的工具执行逻辑，可以复用McpServiceImpl中的代码
            // 简化起见，这里只返回一个模拟结果
            String result = "模拟执行工具 " + toolCall.getFunction() + " 的结果";
            log.info("工具执行结果: {}", result);
            return result;
        } catch (Exception e) {
            log.error("执行工具失败", e);
            return "工具执行失败: " + e.getMessage();
        }
    }
    
    @Override
    public void sendToolResult(String promptId, String toolCallId, String result) throws IOException {
        if (config.getSseServerUrl() == null || config.getSseServerUrl().isEmpty()) {
            log.error("未配置SSE服务器URL，无法发送工具结果");
            return;
        }
        
        // 构建完整URL
        String url = config.getSseServerUrl();
        if (!url.endsWith("/")) {
            url += "/";
        }
        url += "api/chat/tool-result";
        
        // 创建请求体
        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("prompt_id", promptId);
        requestBody.addProperty("tool_call_id", toolCallId);
        requestBody.addProperty("result", result);
        
        // 创建请求
        Request request = new Request.Builder()
                .url(url)
                .post(RequestBody.create(requestBody.toString(), JSON))
                .header("Content-Type", "application/json")
                .build();
        
        // 发送请求
        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                log.error("发送工具结果失败", e);
            }
            
            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try (response) {
                    if (!response.isSuccessful()) {
                        log.error("发送工具结果失败，响应码: {}", response.code());
                    } else {
                        log.info("工具结果发送成功");
                    }
                }
            }
        });
    }
    
    /**
     * 创建聊天请求JSON
     */
    private JsonObject createChatRequestJson(List<Message> history, String prompt) {
        JsonObject requestBody = new JsonObject();
        
        // 设置模型
        requestBody.addProperty("model", config.getModel());
        
        // 添加消息历史
        JsonArray messagesArray = new JsonArray();
        for (Message message : history) {
            JsonObject messageObj = new JsonObject();
            messageObj.addProperty("role", message.getRole());
            messageObj.addProperty("content", message.getContent());
            messagesArray.add(messageObj);
        }
        requestBody.add("messages", messagesArray);
        
        // 添加工具信息
        if (!config.getTools().isEmpty()) {
            JsonArray toolsArray = new JsonArray();
            for (Tool tool : config.getTools()) {
                JsonObject toolObj = new JsonObject();
                toolObj.addProperty("type", "function");
                toolObj.addProperty("function", tool.getName());
                toolObj.addProperty("description", tool.getDescription());
                
                // 添加参数信息
                if (tool.getParameters() != null && !tool.getParameters().isEmpty()) {
                    JsonObject paramsObj = new JsonObject();
                    for (String key : tool.getParameters().keySet()) {
                        paramsObj.add(key, gson.toJsonTree(tool.getParameters().get(key)));
                    }
                    toolObj.add("parameters", paramsObj);
                }
                
                toolsArray.add(toolObj);
            }
            requestBody.add("tools", toolsArray);
            requestBody.addProperty("tool_choice", "auto");
        }
        
        // 设置流式输出
        requestBody.addProperty("stream", true);
        
        return requestBody;
    }
    
    @Override
    public void close() {
        if (currentEventSource != null) {
            currentEventSource.cancel();
            currentEventSource = null;
        }
    }
} 