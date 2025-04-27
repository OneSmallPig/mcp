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
import com.google.gson.JsonElement;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.Map;
import java.util.HashMap;
import java.util.HashSet;

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
    private String currentPromptId;
    
    // 添加活跃请求标记
    private boolean activeRequest = false;
    
    // 添加对话历史记录
    private final List<JsonObject> messageHistory = new ArrayList<>();
    
    // 添加当前活跃的监听器引用
    private SseEventListener currentListener;
    
    public McpSseServiceImpl() {
        this.config = McpClientConfig.getInstance();
        this.clientId = UUID.randomUUID().toString();
        
        // 打印关键配置信息
        log.info("MCP SSE客户端配置状态:");
        log.info("API密钥: {}", config.getApiKey() != null ? "已配置" : "未配置");
        log.info("端点: {}", config.getEndpoint());
        log.info("模型: {}", config.getModel());
        log.info("服务器URL: {}", config.getServerUrl());
        log.info("超时设置: {}秒", config.getTimeout());
        
        // 使用长超时配置，因为SSE连接需要保持更长时间
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(config.getTimeout(), TimeUnit.SECONDS)
                .readTimeout(config.getTimeout() * 2L, TimeUnit.SECONDS)
                .writeTimeout(config.getTimeout(), TimeUnit.SECONDS)
                .build();
        
        this.gson = new Gson();
        
        log.info("MCP SSE服务初始化完成");
        
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
            try {
                currentEventSource.cancel();
            } catch (Exception e) {
                log.warn("关闭旧连接时出错", e);
            } finally {
                currentEventSource = null;
            }
        }
        
        // 检查配置中是否设置了SSE服务器URL
        String url = config.getSseServerUrl();
        if (url == null || url.isEmpty()) {
            log.info("未配置SSE服务器URL，跳过初始连接");
            return;
        }
        
        // 构建SSE连接URL
        if (!url.endsWith("/")) {
            url += "/";
        }
        url += "api/sse";
        
        log.info("建立SSE连接: {}", url);
        
        // 创建请求
        Request request = new Request.Builder()
                .url(url)
                .header("Accept", "text/event-stream;charset=UTF-8")
                .header("Accept-Charset", "UTF-8")
                .header("Cache-Control", "no-cache")
                .header("Connection", "keep-alive")
                .build();
        
        // 创建SSE连接
        try {
            currentEventSource = EventSources.createFactory(httpClient)
                .newEventSource(request, new EventSourceListener() {
                    @Override
                    public void onOpen(EventSource eventSource, Response response) {
                        log.info("初始SSE连接已打开");
                        
                        // 检查响应码
                        if (response.code() != 200) {
                            log.warn("SSE连接返回非200状态码: {}", response.code());
                        }
                        
                        // 设置一个定时器，如果在一定时间内没有收到客户端ID，就重新连接
                        new Thread(() -> {
                            try {
                                // 等待3秒看是否收到了客户端ID
                                Thread.sleep(3000);
                                
                                // 如果客户端ID仍然是默认的UUID，说明没有收到服务器分配的ID
                                if (clientId != null && clientId.equals(UUID.randomUUID().toString())) {
                                    log.warn("在连接打开后3秒内未收到有效的客户端ID，可能需要重新连接");
                                }
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        }).start();
                    }
                    
                    @Override
                    public void onEvent(EventSource eventSource, String id, String type, String data) {
                        // 不仅记录，还要解析可能包含客户端ID的事件
                        log.debug("初始连接收到SSE事件: type={}, id={}, data={}", type, id, data);
                        
                        // 尝试从不同的事件类型和数据格式中提取客户端ID
                        // 1. 检查事件类型
                        if ("client_id".equals(type) || "id".equals(type)) {
                            log.info("从事件类型中获取客户端ID: {}", data);
                            clientId = data;
                            return;
                        }
                        
                        if ("connected".equals(type)) {
                            log.info("收到连接成功事件");
                        }
                        
                        // 处理工具结果事件 - 初始连接也可能接收到工具调用结果
                        if ("tool_result".equals(type)) {
                            log.info("初始连接收到工具结果事件，转发处理: {}", data);
                            try {
                                // 尝试解析为JSON
                                JsonObject toolResultData = gson.fromJson(data, JsonObject.class);
                                
                                String toolCallId = toolResultData.has("tool_call_id") ? 
                                    toolResultData.get("tool_call_id").getAsString() : null;
                                
                                String result = "";
                                if (toolResultData.has("result")) {
                                    result = toolResultData.get("result").getAsString();
                                    log.debug("处理后的工具结果: {}", result);
                                }
                                
                                // 如果有当前活跃的监听器，通知它
                                if (currentListener != null) {
                                    // 通知监听器工具执行结果
                                    if (toolResultData.has("function")) {
                                        String functionName = toolResultData.get("function").getAsString();
                                        currentListener.onToolResult(functionName, result);
                                    } else {
                                        currentListener.onToolResult("unknown_function", result);
                                    }
                                    
                                    // 将工具执行结果直接发送给大模型
                                    if (toolCallId != null && !toolCallId.isEmpty() && currentEventSource != null) {
                                        log.info("初始连接：将工具执行结果直接发送给大模型: {}", result);
                                        // 需要检查是否有活跃会话，如果有，则使用该会话的监听器
                                        if (isActiveRequest() && currentListener instanceof SseSourceListener) {
                                            // 使用当前活跃会话的监听器
                                            sendToolResultWithCurrentListener(toolCallId, result, (SseSourceListener)currentListener);
                                        } else {
                                            // 没有活跃会话，创建一个新的请求
                                            sendToolResultToModel(toolCallId, result, false);
                                        }
                                    }
                                } else {
                                    log.warn("收到工具结果事件，但没有活跃的监听器处理");
                                }
                            } catch (Exception e) {
                                log.error("处理初始连接接收到的工具结果事件时出错", e);
                            }
                            return;
                        }
                        
                        // 2. 尝试解析为JSON并检查客户端ID字段
                        try {
                            JsonObject jsonData = gson.fromJson(data, JsonObject.class);
                            
                            // 检查各种可能的客户端ID字段名
                            String[] possibleFields = {"client_id", "clientId", "id", "connection_id", "connectionId"};
                            for (String field : possibleFields) {
                                if (jsonData.has(field) && !jsonData.get(field).isJsonNull()) {
                                    String extractedId = jsonData.get(field).getAsString();
                                    log.info("从JSON数据中获取客户端ID({}): {}", field, extractedId);
                                    clientId = extractedId;
                                    return;
                                }
                            }
                        } catch (Exception e) {
                            // 如果不是JSON格式，忽略错误
                        }
                        
                        // 3. 检查格式如"client_id:xxxx"的纯文本数据
                        if (data != null && data.contains("client_id:")) {
                            String[] parts = data.split("client_id:");
                            if (parts.length > 1) {
                                String extractedId = parts[1].trim();
                                log.info("从文本数据中获取客户端ID: {}", extractedId);
                                clientId = extractedId;
                            }
                        }
                    }
                    
                    @Override
                    public void onClosed(EventSource eventSource) {
                        log.info("初始SSE连接已关闭");
                        
                        // 如果是在活跃请求期间关闭的，尝试重新连接
                        if (isActiveRequest()) {
                            log.warn("在活跃请求期间连接关闭，尝试重新连接");
                            // 给一点延迟避免立即重连造成服务器压力
                            new Thread(() -> {
                                try {
                                    Thread.sleep(1000);
                                    initSseConnection();
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                }
                            }).start();
                        }
                    }
                    
                    @Override
                    public void onFailure(EventSource eventSource, Throwable t, Response response) {
                        log.error("初始SSE连接失败", t);
                        
                        // 连接失败时清除引用，以便下次重新尝试
                        currentEventSource = null;
                        
                        // 延迟重连，避免快速失败循环
                        new Thread(() -> {
                            try {
                                log.info("等待3秒后重试连接...");
                                Thread.sleep(3000);
                                
                                // 如果有活跃请求，才需要重连
                                if (isActiveRequest()) {
                                    log.info("有活跃请求，尝试重新连接");
                                    initSseConnection();
                                }
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        }).start();
                    }
                });
        } catch (Exception e) {
            log.error("创建初始SSE连接失败", e);
            currentEventSource = null;
            
            // 如果在活跃请求期间连接失败，提供更详细的日志
            if (isActiveRequest()) {
                log.error("在活跃请求期间创建SSE连接失败，这可能导致工具调用无法正确发送到客户端");
            }
        }
    }
    
    /**
     * 检查是否有活跃请求正在处理中
     * @return 是否有活跃请求
     */
    private synchronized boolean isActiveRequest() {
        return activeRequest;
    }

    /**
     * 设置活跃请求状态
     * @param active 请求状态
     */
    private synchronized void setActiveRequest(boolean active) {
        this.activeRequest = active;
    }

    /**
     * 标记请求已完成
     */
    private void markRequestComplete() {
        setActiveRequest(false);
    }
    
    @Override
    public void sendPrompt(String prompt, SseEventListener listener) throws IOException {
        List<Message> history = new ArrayList<>();
        sendPrompt(history, prompt, listener);
    }
    
    @Override
    public void sendPrompt(List<Message> history, String prompt, SseEventListener listener) throws IOException {
        // 检查是否有活跃的请求正在处理
        if (isActiveRequest()) {
            log.warn("有请求正在处理中，请等待当前请求完成");
            throw new IOException("有请求正在处理中，请等待当前请求完成");
        }
        
        // 标记有活跃请求
        setActiveRequest(true);
        
        // 保存当前监听器引用
        this.currentListener = listener;
        
        // 如果连接已关闭或不存在，则创建新连接
        if (currentEventSource == null || currentEventSource.isClosed()) {
            log.info("连接不存在或已关闭，重新初始化SSE连接");
            initSseConnection();
        }
        
        // 生成新的提示ID
        currentPromptId = UUID.randomUUID().toString();
        
        // 清空历史记录，准备新对话
        messageHistory.clear();
        
        // 准备请求体
        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("model", config.getModel());
        
        // 添加消息历史
        JsonArray messagesArray = new JsonArray();
        
        // 添加历史消息
        for (Message message : history) {
            JsonObject msgObj = new JsonObject();
            msgObj.addProperty("role", message.getRole());
            msgObj.addProperty("content", message.getContent());
            messagesArray.add(msgObj);
            
            // 保存到历史记录
            messageHistory.add(msgObj);
        }
        
        // 添加当前用户消息
        JsonObject userMessage = new JsonObject();
        userMessage.addProperty("role", "user");
        userMessage.addProperty("content", prompt);
        messagesArray.add(userMessage);
        
        // 保存到历史记录
        messageHistory.add(userMessage);
        
        requestBody.add("messages", messagesArray);
        
        // 设置流式输出
        requestBody.addProperty("stream", true);
        
        // 添加工具列表
        List<Tool> tools = config.getTools();
        if (tools != null && !tools.isEmpty()) {
            log.info("添加{}个工具到请求中", tools.size());
            
            JsonArray toolsArray = new JsonArray();
            for (Tool tool : tools) {
                JsonObject toolObj = new JsonObject();
                toolObj.addProperty("type", "function");
                
                JsonObject functionObj = new JsonObject();
                functionObj.addProperty("name", tool.getName());
                functionObj.addProperty("description", tool.getDescription());
                
                // 添加参数信息
                if (tool.getParameters() != null && !tool.getParameters().isEmpty()) {
                    JsonObject parametersObj = gson.toJsonTree(tool.getParameters()).getAsJsonObject();
                    functionObj.add("parameters", parametersObj);
                } else {
                    // 如果没有参数，添加一个空对象作为参数
                    JsonObject emptyParams = new JsonObject();
                    emptyParams.addProperty("type", "object");
                    emptyParams.add("properties", new JsonObject());
                    functionObj.add("parameters", emptyParams);
                }
                
                toolObj.add("function", functionObj);
                toolsArray.add(toolObj);
                
                log.debug("添加工具: {}", tool.getName());
            }
            
            requestBody.add("tools", toolsArray);
            requestBody.addProperty("tool_choice", "auto");
        }
        
        // 确定请求URL
        String url = config.getEndpoint();
        
        log.debug("发送SSE请求到: {}", url);
        log.debug("请求体: {}", requestBody);
        
        // 准备请求
        Request.Builder requestBuilder = new Request.Builder()
                .url(url)
                .post(RequestBody.create(requestBody.toString(), JSON));
                
        // 添加火山引擎API密钥头信息
        requestBuilder.header("Authorization", "Bearer " + config.getApiKey());
        
        // 创建SSE事件源
        EventSource.Factory factory = EventSources.createFactory(httpClient);
        currentEventSource = factory.newEventSource(requestBuilder.build(), new SseSourceListener(listener));
    }

    /**
     * SSE事件监听器适配器
     */
    private class SseSourceListener extends EventSourceListener {
        private final SseEventListener userListener;
        private final StringBuilder contentBuilder = new StringBuilder();
        private boolean completed = false;
        
        // 添加事件完整缓存
        private final List<JsonObject> eventCache = new ArrayList<>();
        
        public SseSourceListener(SseEventListener userListener) {
            this.userListener = userListener;
            // 更新当前监听器引用
            currentListener = userListener;
        }
        
        @Override
        public void onOpen(EventSource eventSource, Response response) {
            log.debug("SSE连接已打开: {}", response.code());
            // 通知连接已建立，但不触发任何输出
        }
        
        @Override
        public void onEvent(EventSource eventSource, String id, String type, String data) {
            if (completed) {
                return; // 已经完成，不再处理事件
            }
            
            if (data == null || data.trim().isEmpty() || "[DONE]".equals(data.trim())) {
                log.debug("收到结束事件: {}", data);
                // 处理所有缓存的事件
                processAllEvents();
                completeEvent();
                return;
            }
            
            try {
                // 尝试解析数据为JSON
                JsonObject jsonData = gson.fromJson(data, JsonObject.class);
                
                // 根据事件类型处理
                if ("tool_status".equals(type)) {
                    // 工具状态事件，通知客户端工具开始执行
                    log.info("收到工具状态事件: {}", data);
                    // 可以通知UI显示等待状态等，当前不做特殊处理
                    return;
                }
                
                if ("tool_result".equals(type)) {
                    // 工具结果事件，服务端执行工具后返回结果
                    log.info("收到工具结果事件: {}", data);
                    
                    try {
                        // 尝试解析为JSON
                        JsonObject toolResultData = gson.fromJson(data, JsonObject.class);
                        
                        String toolCallId = toolResultData.has("tool_call_id") ? 
                            toolResultData.get("tool_call_id").getAsString() : null;
                        
                        String result = "";
                        if (toolResultData.has("result")) {
                            result = toolResultData.get("result").getAsString();
                            log.debug("处理后的工具结果: {}", result);
                        }

                        
                        // 通知监听器工具执行结果
                        if (toolResultData.has("function")) {
                            // 如果包含function字段，直接使用
                            String functionName = toolResultData.get("function").getAsString();
                            userListener.onToolResult(functionName, result);
                        } else {
                            // 否则使用通用名称
                            userListener.onToolResult("unknown_function", result);
                        }
                        
                        // 将工具执行结果直接发送给大模型
                        try {
                            if (toolCallId != null && !toolCallId.isEmpty()) {
                                log.info("将工具执行结果直接发送给大模型: {}", result);
                                // 使用当前会话发送工具结果
                                McpSseServiceImpl.this.sendToolResultWithCurrentListener(toolCallId, result, this);
                            } else {
                                log.warn("无法将工具结果发送给大模型，缺少工具调用ID");
                            }
                        } catch (Exception e) {
                            log.error("向大模型发送工具执行结果时出错", e);
                        }
                    } catch (Exception e) {
                        log.error("解析工具结果事件数据时出错", e);
                    }
                    
                    return;
                }
                
                if ("tool_complete".equals(type)) {
                    // 工具完成事件
                    log.info("收到工具完成事件: {}", data);
                    // 可以做一些清理工作
                    return;
                }
                
                // 处理标准的OpenAI格式事件
                if (jsonData.has("choices") && !jsonData.get("choices").isJsonNull()) {
                    JsonObject choiceObj = jsonData.getAsJsonArray("choices")
                            .get(0).getAsJsonObject();
                    
                    // 处理内容增量
                    if (choiceObj.has("delta") && !choiceObj.get("delta").isJsonNull()) {
                        JsonObject deltaObj = choiceObj.getAsJsonObject("delta");
                        
                        // 处理文本内容 - 文本内容可以直接发送，不需等待
                        if (deltaObj.has("content") && !deltaObj.get("content").isJsonNull()) {
                            String content = deltaObj.get("content").getAsString();
                            contentBuilder.append(content);
                            userListener.onTextChunk(content);
                        }
                        
                        // 缓存包含工具调用的事件，不立即处理
                        if (deltaObj.has("tool_calls") && !deltaObj.get("tool_calls").isJsonNull()) {
                            // 将整个事件对象添加到缓存
                            eventCache.add(jsonData);
                        }
                    }
                }
            } catch (Exception e) {
                log.error("解析SSE事件数据时出错", e);
                userListener.onError(e);
            }
        }
        
        /**
         * 处理所有缓存的事件
         */
        private void processAllEvents() {
            if (eventCache.isEmpty()) {
                log.debug("没有缓存的工具调用事件需要处理");
                return;
            }
            
            log.info("开始处理{}个缓存的工具调用事件", eventCache.size());
            
            // 合并所有事件中的工具调用
            Map<String, JsonObject> mergedToolCalls = new HashMap<>();
            Map<Integer, String> indexToIdMap = new HashMap<>();
            
            // 第一步：收集和合并所有工具调用
            for (JsonObject event : eventCache) {
                try {
                    if (event.has("choices") && !event.get("choices").isJsonNull()) {
                        JsonObject choiceObj = event.getAsJsonArray("choices")
                                .get(0).getAsJsonObject();
                        
                        if (choiceObj.has("delta") && !choiceObj.get("delta").isJsonNull()) {
                            JsonObject deltaObj = choiceObj.getAsJsonObject("delta");
                            
                            if (deltaObj.has("tool_calls") && !deltaObj.get("tool_calls").isJsonNull()) {
                                JsonArray toolCalls = deltaObj.getAsJsonArray("tool_calls");
                                
                                for (int i = 0; i < toolCalls.size(); i++) {
                                    JsonObject toolCallObj = toolCalls.get(i).getAsJsonObject();
                                    
                                    // 获取工具调用ID
                                    String toolCallId = "unknown";
                                    Integer index = null;
                                    
                                    if (toolCallObj.has("id") && !toolCallObj.get("id").isJsonNull()) {
                                        toolCallId = toolCallObj.get("id").getAsString();
                                    }
                                    
                                    if (toolCallObj.has("index") && !toolCallObj.get("index").isJsonNull()) {
                                        index = toolCallObj.get("index").getAsInt();
                                        if (toolCallId.equals("unknown")) {
                                            toolCallId = "index_" + index;
                                        }
                                    } else {
                                        if (toolCallId.equals("unknown")) {
                                            // 如果没有ID，使用索引作为ID
                                            toolCallId = "tool_" + i;
                                        }
                                    }
                                    
                                    // 将index和id的关系保存起来
                                    if (index != null) {
                                        String existingId = indexToIdMap.get(index);
                                        if (existingId != null && !existingId.equals(toolCallId) && 
                                            !existingId.startsWith("index_") && !existingId.equals("unknown")) {
                                            // 如果已有非索引生成的ID，则使用该ID
                                            toolCallId = existingId;
                                        } else {
                                            indexToIdMap.put(index, toolCallId);
                                        }
                                    }
                                    
                                    // 从合并映射中获取现有对象或创建新对象
                                    JsonObject mergedToolCall = mergedToolCalls.getOrDefault(toolCallId, new JsonObject());
                                    
                                    // 合并工具调用信息
                                    mergeToolCallObjects(mergedToolCall, toolCallObj);
                                    
                                    // 更新合并映射
                                    mergedToolCalls.put(toolCallId, mergedToolCall);
                                    
                                    // 处理可能存在的同index不同id的情况
                                    if (index != null) {
                                        String idFromIndex = "index_" + index;
                                        if (!toolCallId.equals(idFromIndex) && mergedToolCalls.containsKey(idFromIndex)) {
                                            // 合并同index不同id的工具调用数据
                                            JsonObject indexBasedToolCall = mergedToolCalls.get(idFromIndex);
                                            mergeToolCallObjects(mergedToolCall, indexBasedToolCall);
                                            // 移除index为基础的key
                                            mergedToolCalls.remove(idFromIndex);
                                            // 更新索引到ID的映射
                                            indexToIdMap.put(index, toolCallId);
                                        }
                                    }
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    log.error("合并工具调用事件时出错", e);
                }
            }
            
            log.info("合并后有{}个不同的工具调用", mergedToolCalls.size());
            
            // 第二步：处理合并后的工具调用
            for (Map.Entry<String, JsonObject> entry : mergedToolCalls.entrySet()) {
                String toolCallId = entry.getKey();
                JsonObject mergedToolCall = entry.getValue();
                
                try {
                    ToolCall toolCall = new ToolCall();
                    toolCall.setId(toolCallId);
                    
                    // 提取函数信息
                    if (mergedToolCall.has("function") && !mergedToolCall.get("function").isJsonNull()) {
                        JsonObject functionObj = mergedToolCall.getAsJsonObject("function");
                        
                        // 设置函数名称
                        if (functionObj.has("name") && !functionObj.get("name").isJsonNull()) {
                            toolCall.setFunction(functionObj.get("name").getAsString());
                        } else {
                            log.warn("工具调用缺少函数名称: {}", toolCallId);
                            continue; // 没有函数名称，跳过此工具调用
                        }
                        
                        // 设置参数
                        if (functionObj.has("arguments") && !functionObj.get("arguments").isJsonNull()) {
                            JsonElement argumentsElem = functionObj.get("arguments");
                            Map<String, Object> arguments = new HashMap<>();
                            
                            try {
                                // 处理不同形式的参数
                                if (argumentsElem.isJsonPrimitive()) {
                                    // 是字符串，尝试解析
                                    String argumentsJson = argumentsElem.getAsString();
                                    if (argumentsJson != null && !argumentsJson.isEmpty()) {
                                        arguments = gson.fromJson(argumentsJson, Map.class);
                                    }
                                } else if (argumentsElem.isJsonObject()) {
                                    // 直接是JSON对象
                                    arguments = gson.fromJson(argumentsElem, Map.class);
                                }
                                
                                // 转换参数格式 - 处理嵌套结构
                                arguments = McpSseServiceImpl.this.convertArguments(toolCall.getFunction(), arguments);
                                
                                toolCall.setArguments(arguments);
                            } catch (Exception e) {
                                log.error("解析工具调用参数失败: {}", e.getMessage());
                                // 记录原始参数以便调试
                                Map<String, Object> args = new HashMap<>();
                                args.put("raw", argumentsElem.toString());
                                toolCall.setArguments(args);
                            }
                        } else {
                            // 没有参数，使用空Map
                            toolCall.setArguments(new HashMap<>());
                        }
                        
                        log.info("处理合并后的工具调用: {}({})", toolCall.getFunction(), 
                                 toolCall.getArguments() != null ? gson.toJson(toolCall.getArguments()) : "无参数");
                        
                        // 通知监听器
                        userListener.onToolCall(toolCall);
                        
                        // 执行工具并返回结果
                        try {
                            // 执行工具
                            String result = executeToolCall(toolCall);
                            
                            // 通知监听器工具执行结果
                            userListener.onToolResult(toolCall.getFunction(), result);
                            
                            // 将工具执行结果发送给大模型 - 传递当前SseSourceListener实例确保使用相同的缓冲式应答收集器
                            if (toolCallId != null && !toolCallId.isEmpty()) {
                                log.info("将工具执行结果通过当前会话发送给大模型: {}", result);
                                
                                // 使用当前的用户监听器来确保一致地响应处理
                                McpSseServiceImpl.this.sendToolResultWithCurrentListener(toolCallId, result, this);
                            }
                        } catch (Exception e) {
                            log.error("执行工具时出错", e);
                        }
                    } else {
                        log.warn("工具调用缺少函数信息: {}", toolCallId);
                    }
                } catch (Exception e) {
                    log.error("处理合并后的工具调用时出错: {}", e.getMessage());
                }
            }
            
            // 清空事件缓存
            eventCache.clear();
        }
        
        /**
         * 合并工具调用对象
         */
        private void mergeToolCallObjects(JsonObject target, JsonObject source) {
            // 合并ID
            if (source.has("id") && !source.get("id").isJsonNull()) {
                target.add("id", source.get("id"));
            }
            
            // 合并索引
            if (source.has("index") && !source.get("index").isJsonNull()) {
                target.add("index", source.get("index"));
            }
            
            // 合并类型
            if (source.has("type") && !source.get("type").isJsonNull()) {
                target.add("type", source.get("type"));
            }
            
            // 合并函数信息
            if (source.has("function") && !source.get("function").isJsonNull()) {
                JsonObject sourceFunction = source.getAsJsonObject("function");
                
                if (!target.has("function") || target.get("function").isJsonNull()) {
                    // 目标没有函数信息，直接添加
                    target.add("function", sourceFunction.deepCopy());
                } else {
                    // 合并函数信息
                    JsonObject targetFunction = target.getAsJsonObject("function");
                    
                    // 合并函数名称
                    if (sourceFunction.has("name") && !sourceFunction.get("name").isJsonNull()) {
                        targetFunction.add("name", sourceFunction.get("name"));
                    }
                    
                    // 合并函数参数 - 特殊处理参数字符串的情况
                    if (sourceFunction.has("arguments") && !sourceFunction.get("arguments").isJsonNull()) {
                        JsonElement sourceArgs = sourceFunction.get("arguments");
                        
                        if (!targetFunction.has("arguments") || targetFunction.get("arguments").isJsonNull()) {
                            // 如果目标没有参数，直接添加
                            targetFunction.add("arguments", sourceArgs);
                        } else {
                            JsonElement targetArgs = targetFunction.get("arguments");
                            
                            // 如果两者都是字符串类型的参数
                            if (targetArgs.isJsonPrimitive() && sourceArgs.isJsonPrimitive()) {
                                // 将原始参数字符串和新参数字符串合并
                                String combinedArgs = targetArgs.getAsString() + sourceArgs.getAsString();
                                
                                // 尝试解析合并后的字符串为有效JSON
                                try {
                                    // 检查是否是完整的JSON对象
                                    if (combinedArgs.trim().startsWith("{") && combinedArgs.trim().endsWith("}")) {
                                        // 尝试解析为JSON对象
                                        JsonObject parsedArgs = gson.fromJson(combinedArgs, JsonObject.class);
                                        targetFunction.add("arguments", parsedArgs);
                                    } else {
                                        // 不是完整JSON，保持字符串形式
                                        targetFunction.addProperty("arguments", combinedArgs);
                                    }
                                } catch (Exception e) {
                                    // 解析失败，保持字符串形式
                                    targetFunction.addProperty("arguments", combinedArgs);
                                }
                            } else if (targetArgs.isJsonObject() && sourceArgs.isJsonObject()) {
                                // 如果两者都是JSON对象，递归合并
                                mergeJsonObjects(targetArgs.getAsJsonObject(), sourceArgs.getAsJsonObject());
                            } else if (targetArgs.isJsonPrimitive() && sourceArgs.isJsonObject()) {
                                // 目标是字符串，源是对象
                                if (targetArgs.getAsString().isEmpty()) {
                                    // 如果目标是空字符串，使用源对象
                                    targetFunction.add("arguments", sourceArgs);
                                } else {
                                    // 尝试将目标字符串解析为JSON对象
                                    try {
                                        JsonObject parsedTargetArgs = gson.fromJson(targetArgs.getAsString(), JsonObject.class);
                                        mergeJsonObjects(parsedTargetArgs, sourceArgs.getAsJsonObject());
                                        targetFunction.add("arguments", parsedTargetArgs);
                                    } catch (Exception e) {
                                        // 解析失败，使用源对象
                                        targetFunction.add("arguments", sourceArgs);
                                    }
                                }
                            } else {
                                // 其他情况，优先使用非空的参数
                                if ((sourceArgs.isJsonPrimitive() && !sourceArgs.getAsString().isEmpty()) ||
                                    (sourceArgs.isJsonObject() && sourceArgs.getAsJsonObject().size() > 0)) {
                                    targetFunction.add("arguments", sourceArgs);
                                }
                            }
                        }
                    }
                }
            }
        }
        
        /**
         * 递归合并两个JsonObject对象
         */
        private void mergeJsonObjects(JsonObject target, JsonObject source) {
            for (Map.Entry<String, JsonElement> entry : source.entrySet()) {
                String key = entry.getKey();
                JsonElement value = entry.getValue();
                
                if (value.isJsonObject() && target.has(key) && target.get(key).isJsonObject()) {
                    // 递归合并嵌套对象
                    mergeJsonObjects(target.getAsJsonObject(key), value.getAsJsonObject());
                } else {
                    // 直接替换或添加值
                    target.add(key, value);
                }
            }
        }
        
        /**
         * 安全地触发一次完成事件
         */
        private synchronized void completeEvent() {
            if (completed) {
                return;
            }
            
            completed = true;
            
            // 如果内容为空，但有工具调用，可能需要等待工具结果
            if (contentBuilder.length() == 0 && !eventCache.isEmpty()) {
                log.info("无文本内容但有工具调用，等待工具执行完成");
            }
            
            log.debug("完成事件处理");
            
            // 通知用户监听器完成
            userListener.onComplete();
            
            // 标记请求已完成
            markRequestComplete();
        }
        
        @Override
        public void onFailure(EventSource eventSource, Throwable t, Response response) {
            log.error("SSE连接失败: {}", t.getMessage());
            
            // 通知用户监听器错误
            userListener.onError(t);
            
            // 标记请求已完成
            markRequestComplete();
        }
        
        @Override
        public void onClosed(EventSource eventSource) {
            log.debug("SSE连接已关闭");
            // 不再这里触发onComplete，改为在收到[DONE]事件时触发
            // 如果没有收到[DONE]但连接关闭，才触发完成
            if (!completed) {
                completeEvent();
            }
            
            // 确保标记请求已完成
            markRequestComplete();
        }
    }
    
    /**
     * 执行工具调用
     * 
     * @param toolCall 工具调用信息
     * @return 工具执行结果
     */
    private String executeToolCall(ToolCall toolCall) {
        log.info("开始执行工具: {}", toolCall.getFunction());
        
        // 首先检查是否有对应的工具定义
        List<Tool> availableTools = config.getTools();
        boolean toolExists = false;
        
        // 记录所有可用的工具名称
        StringBuilder availableToolNames = new StringBuilder();
        for (Tool tool : availableTools) {
            if (availableToolNames.length() > 0) {
                availableToolNames.append(", ");
            }
            availableToolNames.append(tool.getName());
            
            if (tool.getName().equals(toolCall.getFunction())) {
                toolExists = true;
            }
        }
        
        if (!toolExists) {
            String errorMsg = "工具 " + toolCall.getFunction() + " 不存在或未配置。可用工具: " + 
                              (availableToolNames.length() > 0 ? availableToolNames.toString() : "无");
            log.warn(errorMsg);
            return errorMsg;
        }
        
        // 如果是使用MCP协议，应该将工具调用请求发送给MCP服务器
        if (config.isUseMcpProtocol() || config.isUseLocalServer()) {
            return executeToolViaServer(toolCall);
        }

        // 默认返回一个通用消息
        return "工具 " + toolCall.getFunction() + " 执行成功，但没有具体实现逻辑。";
    }
    
    /**
     * 通过MCP服务器执行工具
     * 
     * @param toolCall 工具调用信息
     * @return 工具执行结果
     */
    private String executeToolViaServer(ToolCall toolCall) {
        log.info("通过服务器执行工具: {}", toolCall.getFunction());
        
        // 检查客户端 ID 是否有效
        if (this.clientId == null || this.clientId.isEmpty() || this.clientId.equals(UUID.randomUUID().toString())) {
            log.warn("客户端ID无效或未获取到服务器分配的ID，可能导致服务端无法找到对应连接");
            // 尝试重新建立连接获取有效的客户端ID
            if (currentEventSource == null || currentEventSource.isClosed()) {
                log.info("尝试重新建立连接以获取有效的客户端ID");
                initSseConnection();
                // 给一点时间让服务器分配ID
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        
        // 尝试多种可能的API路径和格式
        List<String> possibleApis = new ArrayList<>();
        possibleApis.add("api/tools/execute");         // 标准格式
        
        Exception lastException = null;
        
        // 构建基础URL
        String baseUrl = config.getServerUrl();
        if (!baseUrl.endsWith("/")) {
            baseUrl += "/";
        }
        
        // 尝试每种可能的API路径
        for (String apiPath : possibleApis) {
            String url = baseUrl + apiPath;
            
            try {
                // 创建标准请求格式
                JsonObject requestBody = new JsonObject();
                requestBody.addProperty("tool_name", toolCall.getFunction());
                
                // 添加工具参数
                if (toolCall.getArguments() != null && !toolCall.getArguments().isEmpty()) {
                    JsonObject argsObj = gson.toJsonTree(toolCall.getArguments()).getAsJsonObject();
                    requestBody.add("arguments", argsObj);
                } else {
                    requestBody.add("arguments", new JsonObject());
                }
                
                // 添加调用ID以便追踪 - 使用服务器分配的clientId
                // 如果服务器没有分配ID，则使用currentPromptId作为备选方案
                if (this.clientId != null && !this.clientId.isEmpty() && !this.clientId.equals(UUID.randomUUID().toString())) {
                    requestBody.addProperty("client_id", this.clientId);
                    log.debug("使用服务器分配的客户端ID: {}", this.clientId);
                } else {
                    // 如果没有有效的客户端ID，使用提示ID，并明确警告
                    requestBody.addProperty("client_id", this.currentPromptId);
                    log.warn("未找到服务器分配的客户端ID，使用当前提示ID: {}，这可能导致服务端无法找到连接", this.currentPromptId);
                }
                
                log.debug("尝试工具执行API: {} 请求: {}", url, requestBody);
                
                // 创建POST请求
                Request request = new Request.Builder()
                        .url(url)
                        .post(RequestBody.create(requestBody.toString(), JSON))
                        .header("Content-Type", "application/json")
                        .build();
        
                // 发送请求
                try (Response response = httpClient.newCall(request).execute()) {
                    if (response.isSuccessful()) {
                        // 解析响应
                        String responseBody = response.body().string();
                        log.debug("工具执行响应: {}", responseBody);
                        
                        try {
                            // 尝试解析为JSON
                            JsonObject resultObj = gson.fromJson(responseBody, JsonObject.class);
                            
                            // 处理几种可能的响应格式
                            if (resultObj.has("result")) {
                                return resultObj.get("result").getAsString();
                            } else if (resultObj.has("response")) {
                                return resultObj.get("response").getAsString();
                            } else if (resultObj.has("data")) {
                                // 处理data字段，可能包含结果
                                JsonElement dataElem = resultObj.get("data");
                                if (dataElem.isJsonObject()) {
                                    JsonObject dataObj = dataElem.getAsJsonObject();
                                    if (dataObj.has("result")) {
                                        return dataObj.get("result").getAsString();
                                    }
                                } else if (dataElem.isJsonPrimitive()) {
                                    return dataElem.getAsString();
                                }
                            }
                            
                            // 检查错误信息
                            if (resultObj.has("error")) {
                                String errorMsg = resultObj.get("error").getAsString();
                                // 检查是否是连接中断错误
                                if (errorMsg.contains("连接已断开") || errorMsg.contains("连接状态异常")) {
                                    log.error("服务器报告连接已断开，尝试重新建立连接");
                                    // 尝试重新建立连接
                                    initSseConnection();
                                }
                                
                                String error = "工具执行错误: " + errorMsg;
                                log.error(error);
                                return error;
                            }
                            
                            // 如果没有找到明确的结果字段，但响应成功，可能整个响应就是结果
                            return responseBody;
                        } catch (Exception e) {
                            // 如果不是JSON格式，直接返回响应体作为结果
                            return responseBody;
                        }
                    } else {
                        // 本路径未成功，尝试下一个
                        log.debug("API路径 {} 返回状态码: {}, 尝试下一个路径", url, response.code());
                        lastException = new IOException("状态码: " + response.code());
                    }
                }
            } catch (Exception e) {
                log.debug("尝试API路径 {} 时出错: {}", url, e.getMessage());
                lastException = e;
                // 继续尝试下一个路径
            }
        }
        
        // 所有路径都尝试过，但都失败了
        String error = "所有工具执行API路径都尝试失败";
        if (lastException != null) {
            error += ": " + lastException.getMessage();
        }
        log.error(error);
        return error;
    }
    
    @Override
    public void close() {
        log.info("正在关闭MCP SSE服务...");
        
        // 等待活跃请求完成
        if (isActiveRequest()) {
            log.info("等待活跃请求完成...");
            try {
                // 简单等待一段时间让请求完成
                for (int i = 0; i < 30 && isActiveRequest(); i++) {
                    Thread.sleep(100);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        
        if (currentEventSource != null) {
            try {
                // 先尝试优雅关闭
                currentEventSource.close();
            } catch (Exception e) {
                log.warn("正常关闭SSE连接时出错", e);
                
                try {
                    // 如果优雅关闭失败，尝试强制取消
                    currentEventSource.cancel();
                } catch (Exception ex) {
                    log.warn("强制取消SSE连接时出错", ex);
                }
            } finally {
                // 无论如何清除引用
                currentEventSource = null;
            }
        }
        
        // 确保请求标记被重置
        setActiveRequest(false);
        log.info("MCP SSE服务已关闭");
    }
    
    /**
     * 转换参数格式，处理嵌套结构
     * 
     * @param toolName 工具名称
     * @param arguments 原始参数
     * @return 转换后的参数
     */
    private Map<String, Object> convertArguments(String toolName, Map<String, Object> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return arguments;
        }
        
        log.debug("转换参数格式，原始参数: {}", gson.toJson(arguments));
        
        // 检查是否有嵌套对象
        for (String key : new HashSet<>(arguments.keySet())) {
            Object value = arguments.get(key);
            if (value instanceof Map) {
                // 发现嵌套Map，提取其内容合并到顶层
                @SuppressWarnings("unchecked")
                Map<String, Object> nestedMap = (Map<String, Object>) value;
                
                log.debug("发现嵌套参数: {} = {}", key, gson.toJson(nestedMap));
                
                // 移除嵌套对象
                arguments.remove(key);
                
                // 将嵌套对象的内容合并到顶层
                arguments.putAll(nestedMap);
                
                log.debug("参数转换后: {}", gson.toJson(arguments));
                return arguments;
            }
        }
        
        return arguments;
    }

    /**
     * 将工具执行结果直接发送给大模型
     * 
     * @param toolCallId 工具调用ID
     * @param result 工具执行结果
     * @param streamOutput 是否使用流式输出
     */
    private void sendToolResultToModel(String toolCallId, String result, boolean streamOutput) {
        try {
            log.info("发送工具结果给大模型，工具ID: {}, 结果: {}", toolCallId, result);
            
            // 准备请求体
            JsonObject requestBody = new JsonObject();
            requestBody.addProperty("model", config.getModel());
            
            // 创建消息列表，包含完整历史
            JsonArray messagesArray = new JsonArray();
            
            // 添加之前的消息历史
            for (JsonObject historyMessage : messageHistory) {
                messagesArray.add(historyMessage);
            }
            
            // 添加新的工具结果消息
            JsonObject toolResultMessage = new JsonObject();
            toolResultMessage.addProperty("role", "tool");
            toolResultMessage.addProperty("tool_call_id", toolCallId);
            toolResultMessage.addProperty("content", result);
            messagesArray.add(toolResultMessage);
            
            // 将工具结果消息添加到历史
            messageHistory.add(toolResultMessage);
            
            requestBody.add("messages", messagesArray);
            
            // 设置流式输出
            requestBody.addProperty("stream", true);
            
            // 添加工具列表
            List<Tool> tools = config.getTools();
            if (tools != null && !tools.isEmpty()) {
                JsonArray toolsArray = new JsonArray();
                for (Tool tool : tools) {
                    JsonObject toolObj = new JsonObject();
                    toolObj.addProperty("type", "function");
                    
                    JsonObject functionObj = new JsonObject();
                    functionObj.addProperty("name", tool.getName());
                    functionObj.addProperty("description", tool.getDescription());
                    
                    // 添加参数信息
                    if (tool.getParameters() != null && !tool.getParameters().isEmpty()) {
                        JsonObject parametersObj = gson.toJsonTree(tool.getParameters()).getAsJsonObject();
                        functionObj.add("parameters", parametersObj);
                    } else {
                        // 如果没有参数，添加一个空对象作为参数
                        JsonObject emptyParams = new JsonObject();
                        emptyParams.addProperty("type", "object");
                        emptyParams.add("properties", new JsonObject());
                        functionObj.add("parameters", emptyParams);
                    }
                    
                    toolObj.add("function", functionObj);
                    toolsArray.add(toolObj);
                }
                
                requestBody.add("tools", toolsArray);
                requestBody.addProperty("tool_choice", "auto");
            }
            
            // 发送请求
            String url = config.getEndpoint();
            
            log.debug("发送工具结果到大模型: {}", url);
            log.debug("请求体: {}", requestBody);
            
            // 准备请求
            Request.Builder requestBuilder = new Request.Builder()
                    .url(url)
                    .post(RequestBody.create(requestBody.toString(), JSON));
                    
            // 添加火山引擎API密钥头信息
            requestBuilder.header("Authorization", "Bearer " + config.getApiKey());
            
            // 使用与普通交互相同的事件监听器，确保一致的用户体验
            EventSource.Factory factory = EventSources.createFactory(httpClient);
            EventSource eventSource = factory.newEventSource(
                requestBuilder.build(), 
                new SseSourceListener(currentListener)
            );
            
            log.debug("开始接收大模型对工具结果的响应");
        } catch (Exception e) {
            log.error("发送工具结果给大模型时发生错误", e);
        }
    }

    /**
     * 使用当前正在进行的对话会话发送工具执行结果给大模型
     * 这确保了工具结果处理使用与用户提问相同的监听器，保持一致的响应体验
     * 
     * @param toolCallId 工具调用ID
     * @param result 工具执行结果
     * @param currentListener 当前会话的监听器
     */
    private void sendToolResultWithCurrentListener(String toolCallId, String result, SseSourceListener currentListener) {
        try {
            log.info("使用当前会话发送工具结果给大模型，工具ID: {}, 结果: {}", toolCallId, result);
            
            // 准备请求体
            JsonObject requestBody = new JsonObject();
            requestBody.addProperty("model", config.getModel());
            
            // 创建消息列表，包含完整历史
            JsonArray messagesArray = new JsonArray();
            
            // 添加之前的消息历史
            for (JsonObject historyMessage : messageHistory) {
                messagesArray.add(historyMessage);
            }
            
            // 添加新的工具结果消息
            JsonObject toolResultMessage = new JsonObject();
            toolResultMessage.addProperty("role", "tool");
            toolResultMessage.addProperty("tool_call_id", toolCallId);
            toolResultMessage.addProperty("content", result);
            messagesArray.add(toolResultMessage);
            
            // 将工具结果消息添加到历史
            messageHistory.add(toolResultMessage);
            
            requestBody.add("messages", messagesArray);
            
            // 设置流式输出
            requestBody.addProperty("stream", true);
            
            // 添加工具列表
            List<Tool> tools = config.getTools();
            if (tools != null && !tools.isEmpty()) {
                JsonArray toolsArray = new JsonArray();
                for (Tool tool : tools) {
                    JsonObject toolObj = new JsonObject();
                    toolObj.addProperty("type", "function");
                    
                    JsonObject functionObj = new JsonObject();
                    functionObj.addProperty("name", tool.getName());
                    functionObj.addProperty("description", tool.getDescription());
                    
                    // 添加参数信息
                    if (tool.getParameters() != null && !tool.getParameters().isEmpty()) {
                        JsonObject parametersObj = gson.toJsonTree(tool.getParameters()).getAsJsonObject();
                        functionObj.add("parameters", parametersObj);
                    } else {
                        // 如果没有参数，添加一个空对象作为参数
                        JsonObject emptyParams = new JsonObject();
                        emptyParams.addProperty("type", "object");
                        emptyParams.add("properties", new JsonObject());
                        functionObj.add("parameters", emptyParams);
                    }
                    
                    toolObj.add("function", functionObj);
                    toolsArray.add(toolObj);
                }
                
                requestBody.add("tools", toolsArray);
                requestBody.addProperty("tool_choice", "auto");
            }
            
            // 发送请求
            String url = config.getEndpoint();
            
            log.debug("发送工具结果到大模型: {}", url);
            log.debug("请求体: {}", requestBody);
            
            // 准备请求
            Request.Builder requestBuilder = new Request.Builder()
                    .url(url)
                    .post(RequestBody.create(requestBody.toString(), JSON));
                    
            // 添加火山引擎API密钥头信息
            requestBuilder.header("Authorization", "Bearer " + config.getApiKey());
            
            // 创建SSE事件源来处理大模型的流式响应，使用传入的当前监听器
            // 这样所有的响应都会通过同一个监听器处理，保持一致的响应体验
            EventSource.Factory factory = EventSources.createFactory(httpClient);
            EventSource eventSource = factory.newEventSource(requestBuilder.build(), currentListener);
            
            log.debug("开始通过当前会话接收大模型对工具结果的响应");
        } catch (Exception e) {
            log.error("通过当前会话发送工具结果给大模型时发生错误", e);
        }
    }
} 