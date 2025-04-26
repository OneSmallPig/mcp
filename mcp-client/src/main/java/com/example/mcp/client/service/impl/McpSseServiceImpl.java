package com.example.mcp.client.service.impl;

import com.example.mcp.client.config.McpClientConfig;
import com.example.mcp.client.model.Message;
import com.example.mcp.client.model.Tool;
import com.example.mcp.client.model.ToolCall;
import com.example.mcp.client.service.McpSseService;
import com.example.mcp.client.service.SseEventListener;
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
 * 基于Cursor的MCP SSE通信方式进行优化
 */
public class McpSseServiceImpl implements McpSseService {
    private static final Logger log = LoggerFactory.getLogger(McpSseServiceImpl.class);
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    
    private final OkHttpClient httpClient;
    private final Gson gson;
    private final McpClientConfig config;
    private EventSource currentEventSource;
    private String clientId;
    
    public McpSseServiceImpl() {
        this.config = McpClientConfig.getInstance();
        this.clientId = UUID.randomUUID().toString();
        
        // 创建支持长连接的HTTP客户端
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(config.getTimeout(), TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.SECONDS) // 无限读取超时，用于SSE长连接
                .writeTimeout(config.getTimeout(), TimeUnit.SECONDS)
                .build();
        
        this.gson = new Gson();
        
        log.info("MCP SSE服务初始化完成");
        log.info("SSE服务器URL: {}", config.getSseServerUrl());
        log.info("SSE客户端ID: {}", clientId);
        
        // 初始化时建立SSE连接
        initSseConnection();
    }
    
    /**
     * 初始化SSE连接
     */
    private void initSseConnection() {
        if (currentEventSource != null && !currentEventSource.isClosed()) {
            log.debug("SSE连接已建立，无需重新连接");
            return;
        }
        
        // 关闭可能存在的连接
        if (currentEventSource != null) {
            currentEventSource.cancel();
        }
        
        // 构建SSE连接URL
        String url = config.getSseServerUrl();
        if (!url.endsWith("/")) {
            url += "/";
        }
        url += "api/sse";
        
        log.info("建立SSE连接: {}", url);
        
        // 创建请求
        Request request = new Request.Builder()
                .url(url)
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
                        // 这里不处理事件，只记录
                        log.debug("收到未处理的SSE事件: type={}, id={}, data={}", type, id, data);
                    }
                    
                    @Override
                    public void onClosed(EventSource eventSource) {
                        log.info("SSE连接已关闭");
                    }
                    
                    @Override
                    public void onFailure(EventSource eventSource, Throwable t, Response response) {
                        log.error("SSE连接失败", t);
                    }
                });
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
        // 确保SSE连接已建立
        initSseConnection();
        
        // 向AI模型发送请求
        String llmUrl = config.getEndpoint(); // 直接向大模型服务端点发送请求
        if (llmUrl == null || llmUrl.isEmpty()) {
            llmUrl = config.getSseServerUrl(); // 如果未配置，使用SSE服务器URL作为替代
            if (!llmUrl.endsWith("/")) {
                llmUrl += "/";
            }
            llmUrl += "api/llm/chat"; // 假设大模型服务端点路径
        }
        
        log.info("向大模型发送请求: {}", llmUrl);
        
        // 创建请求体
        JsonObject requestBody = createChatRequestJson(history, prompt);
        requestBody.addProperty("client_id", clientId); // 添加客户端ID，以便服务器能够向该客户端发送SSE事件
        
        // 创建请求
        Request request = new Request.Builder()
                .url(llmUrl)
                .post(RequestBody.create(requestBody.toString(), JSON))
                .header("Content-Type", "application/json")
                .build();
        
        // 重新注册SSE监听器，以接收大模型的响应
        registerSseListener(listener);
        
        // 发送请求
        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                log.error("发送请求到大模型失败", e);
                listener.onError(e);
            }
            
            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful()) {
                    log.error("请求大模型响应错误: {}", response.code());
                    listener.onError(new IOException("请求大模型响应错误: " + response.code()));
                    return;
                }
                
                String responseBody = response.body().string();
                log.debug("大模型请求确认: {}", responseBody);
                // 响应处理完毕后，将通过SSE发送事件
            }
        });
    }
    
    /**
     * 为当前SSE连接注册监听器
     */
    private void registerSseListener(SseEventListener listener) {
        if (currentEventSource != null) {
            // 取消已有连接并创建新连接
            currentEventSource.cancel();
        }
        
        // 构建SSE连接URL
        String url = config.getSseServerUrl();
        if (!url.endsWith("/")) {
            url += "/";
        }
        url += "api/sse";
        
        log.info("重新建立带监听器的SSE连接: {}", url);
        
        Request request = new Request.Builder()
                .url(url)
                .header("Accept", "text/event-stream")
                .build();
                
        // 创建新连接并设置监听器
        currentEventSource = EventSources.createFactory(httpClient)
                .newEventSource(request, new EventSourceListener() {
                    @Override
                    public void onOpen(EventSource eventSource, Response response) {
                        log.info("带监听器的SSE连接已打开");
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
                case "connected":
                    // 连接成功，可以更新clientId
                    if (jsonData.has("client_id")) {
                        clientId = jsonData.get("client_id").getAsString();
                        log.info("更新客户端ID: {}", clientId);
                    }
                    break;
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
            
            // 发送HTTP请求到MCP服务器执行工具
            String url = config.getServerUrl();
            if (!url.endsWith("/")) {
                url += "/";
            }
            url += "api/mcp/execute";
            
            // 创建请求体
            JsonObject requestBody = new JsonObject();
            requestBody.addProperty("client_id", clientId);
            requestBody.addProperty("function", toolCall.getFunction());
            
            // 添加参数
            if (toolCall.getArguments() != null) {
                JsonObject argumentsJson = new JsonObject();
                for (Map.Entry<String, Object> entry : toolCall.getArguments().entrySet()) {
                    if (entry.getValue() instanceof String) {
                        argumentsJson.addProperty(entry.getKey(), (String) entry.getValue());
                    } else if (entry.getValue() instanceof Number) {
                        argumentsJson.addProperty(entry.getKey(), (Number) entry.getValue());
                    } else if (entry.getValue() instanceof Boolean) {
                        argumentsJson.addProperty(entry.getKey(), (Boolean) entry.getValue());
                    } else {
                        // 将复杂对象转换为JSON
                        argumentsJson.add(entry.getKey(), gson.toJsonTree(entry.getValue()));
                    }
                }
                requestBody.add("arguments", argumentsJson);
            }
            
            // 发送请求
            Request request = new Request.Builder()
                    .url(url)
                    .post(RequestBody.create(requestBody.toString(), JSON))
                    .header("Content-Type", "application/json")
                    .build();
            
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String error = "执行工具失败，状态码: " + response.code();
                    log.error(error);
                    return error;
                }
                
                String responseBody = response.body().string();
                JsonObject jsonResponse = gson.fromJson(responseBody, JsonObject.class);
                
                if (jsonResponse.has("result")) {
                    String result = jsonResponse.get("result").getAsString();
                    log.info("工具执行结果: {}", result);
                    return result;
                } else if (jsonResponse.has("error")) {
                    String error = "执行工具错误: " + jsonResponse.get("error").getAsString();
                    log.error(error);
                    return error;
                } else {
                    return responseBody; // 返回原始响应
                }
            }
        } catch (Exception e) {
            log.error("执行工具失败", e);
            return "工具执行失败: " + e.getMessage();
        }
    }
    
    @Override
    public void sendToolResult(String toolCallId, String id, String result) throws IOException {
        if (config.getSseServerUrl() == null || config.getSseServerUrl().isEmpty()) {
            log.error("未配置SSE服务器URL，无法发送工具结果");
            return;
        }
        
        // 构建完整URL
        String url = config.getSseServerUrl();
        if (!url.endsWith("/")) {
            url += "/";
        }
        url += "api/tool-result";
        
        // 创建请求体
        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("client_id", clientId);
        requestBody.addProperty("tool_call_id", toolCallId);
        requestBody.addProperty("id", id);
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
                if (!response.isSuccessful()) {
                    log.error("发送工具结果响应错误: {}", response.code());
                    return;
                }
                
                String responseBody = response.body().string();
                log.debug("发送工具结果响应: {}", responseBody);
            }
        });
    }
    
    /**
     * 创建聊天请求JSON
     */
    private JsonObject createChatRequestJson(List<Message> history, String prompt) {
        JsonObject requestJson = new JsonObject();
        requestJson.addProperty("model", config.getModel());
        
        // 添加消息历史
        if (history != null && !history.isEmpty()) {
            JsonArray messagesArray = new JsonArray();
            for (Message message : history) {
                JsonObject messageObj = new JsonObject();
                messageObj.addProperty("role", message.getRole());
                messageObj.addProperty("content", message.getContent());
                messagesArray.add(messageObj);
            }
            requestJson.add("messages", messagesArray);
        } else {
            // 如果未提供历史，创建一个只包含用户提示的消息
            JsonArray messagesArray = new JsonArray();
            
            // 添加系统消息
            JsonObject systemMessage = new JsonObject();
            systemMessage.addProperty("role", "system");
            systemMessage.addProperty("content", "You are a helpful assistant. When user requests a tool function, always try to call the available tools to satisfy their request.");
            messagesArray.add(systemMessage);
            
            // 添加用户消息
            JsonObject userMessage = new JsonObject();
            userMessage.addProperty("role", "user");
            userMessage.addProperty("content", prompt);
            messagesArray.add(userMessage);
            
            requestJson.add("messages", messagesArray);
        }
        
        // 添加工具列表
        List<Tool> tools = config.getTools();
        if (tools != null && !tools.isEmpty()) {
            JsonArray toolsArray = new JsonArray();
            for (Tool tool : tools) {
                JsonObject toolObj = new JsonObject();
                toolObj.addProperty("type", "function");
                toolObj.addProperty("function", tool.getName());
                
                JsonObject functionObj = new JsonObject();
                functionObj.addProperty("name", tool.getName());
                functionObj.addProperty("description", tool.getDescription());
                
                // 添加参数信息
                JsonObject parametersObj = new JsonObject();
                if (tool.getParameters() != null && !tool.getParameters().isEmpty()) {
                    JsonObject propertiesObj = new JsonObject();
                    for (Map.Entry<String, Object> entry : tool.getParameters().entrySet()) {
                        JsonObject paramObj = new JsonObject();
                        Object param = entry.getValue();
                        if (param instanceof Map) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> paramMap = (Map<String, Object>) param;
                            for (Map.Entry<String, Object> paramEntry : paramMap.entrySet()) {
                                if (paramEntry.getValue() instanceof String) {
                                    paramObj.addProperty(paramEntry.getKey(), (String) paramEntry.getValue());
                                }
                            }
                        }
                        propertiesObj.add(entry.getKey(), paramObj);
                    }
                    parametersObj.add("properties", propertiesObj);
                    
                    // 添加必需参数
                    if (tool.getRequiredParameters() != null && !tool.getRequiredParameters().isEmpty()) {
                        JsonArray requiredArray = new JsonArray();
                        for (String required : tool.getRequiredParameters()) {
                            requiredArray.add(required);
                        }
                        parametersObj.add("required", requiredArray);
                    }
                }
                
                // 添加参数类型信息
                parametersObj.addProperty("type", "object");
                functionObj.add("parameters", parametersObj);
                
                toolObj.add("function", functionObj);
                toolsArray.add(toolObj);
            }
            requestJson.add("tools", toolsArray);
            
            // 默认启用工具调用
            requestJson.addProperty("tool_choice", "auto");
        }
        
        // 添加温度等其他参数
        if (config.getTemperature() > 0) {
            requestJson.addProperty("temperature", config.getTemperature());
        }
        
        return requestJson;
    }
    
    @Override
    public void close() {
        if (currentEventSource != null) {
            currentEventSource.cancel();
            currentEventSource = null;
        }
        log.info("已关闭MCP SSE服务");
    }
} 