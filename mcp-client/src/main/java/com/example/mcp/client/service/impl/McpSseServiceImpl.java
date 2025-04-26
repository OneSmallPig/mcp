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
            currentEventSource = null;
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
                .header("Accept", "text/event-stream")
                .build();
        
        // 创建SSE连接
        try {
            currentEventSource = EventSources.createFactory(httpClient)
                    .newEventSource(request, new EventSourceListener() {
                        @Override
                        public void onOpen(EventSource eventSource, Response response) {
                            log.info("初始SSE连接已打开");
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
                                    return;
                                }
                            }
                        }
                        
                        @Override
                        public void onClosed(EventSource eventSource) {
                            log.info("初始SSE连接已关闭");
                        }
                        
                        @Override
                        public void onFailure(EventSource eventSource, Throwable t, Response response) {
                            log.error("初始SSE连接失败", t);
                            // 连接失败时清除引用，以便下次重新尝试
                            currentEventSource = null;
                        }
                    });
        } catch (Exception e) {
            log.error("创建初始SSE连接失败", e);
            currentEventSource = null;
        }
    }
    
    @Override
    public void sendPrompt(String prompt, SseEventListener listener) throws IOException {
        List<Message> history = new ArrayList<>();
        sendPrompt(history, prompt, listener);
    }
    
    @Override
    public void sendPrompt(List<Message> history, String prompt, SseEventListener listener) throws IOException {
        // 关闭任何已存在的EventSource连接
        close();
        
        // 生成新的提示ID
        currentPromptId = UUID.randomUUID().toString();
        
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
        }
        
        // 添加当前用户消息
        JsonObject userMessage = new JsonObject();
        userMessage.addProperty("role", "user");
        userMessage.addProperty("content", prompt);
        messagesArray.add(userMessage);
        
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
     * 获取完整的服务器路径URL
     */
    private String getFullServerPath() {
        String baseUrl = config.getServerUrl();
        String path = config.getServerPath();
        
        if (!baseUrl.endsWith("/") && !path.startsWith("/")) {
            return baseUrl + "/" + path;
        } else if (baseUrl.endsWith("/") && path.startsWith("/")) {
            return baseUrl + path.substring(1);
        } else {
            return baseUrl + path;
        }
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
                // 解析数据
                JsonObject jsonData = gson.fromJson(data, JsonObject.class);
                
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
                            
                            // 发送结果给服务器
                            sendToolResult(currentPromptId, toolCall.getId(), result);
                        } catch (Exception e) {
                            log.error("执行工具时出错", e);
                            // 发送错误结果
                            try {
                                sendToolResult(currentPromptId, toolCall.getId(), 
                                       "执行工具时出错: " + e.getMessage());
                            } catch (Exception ex) {
                                log.error("发送工具执行错误结果时出错", ex);
                            }
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
                            } else if (targetArgs.isJsonObject() && sourceArgs.isJsonPrimitive()) {
                                // 目标是对象，源是字符串
                                if (sourceArgs.getAsString().isEmpty()) {
                                    // 保持目标对象不变
                                } else {
                                    // 尝试将源字符串解析为JSON对象
                                    try {
                                        JsonObject parsedSourceArgs = gson.fromJson(sourceArgs.getAsString(), JsonObject.class);
                                        mergeJsonObjects(targetArgs.getAsJsonObject(), parsedSourceArgs);
                                    } catch (Exception e) {
                                        // 解析失败，忽略
                                        log.debug("无法将源参数字符串解析为JSON对象: {}", sourceArgs.getAsString());
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
            if (!completed) {
                completed = true;
                userListener.onComplete();
            }
        }
        
        @Override
        public void onFailure(EventSource eventSource, Throwable t, Response response) {
            String errorMsg;
            if (response != null) {
                errorMsg = "SSE连接失败，HTTP状态码: " + response.code();
                log.error("{}，原因: {}", errorMsg, response.message());
                
                // 特殊处理401错误
                if (response.code() == 401) {
                    errorMsg = "API认证失败(401)。请检查火山引擎API密钥配置是否正确。";
                    log.error("火山引擎API认证失败，请检查application.yml中的apiKey配置。");
                }
                
                userListener.onError(new IOException(errorMsg));
            } else {
                log.error("SSE连接失败", t);
                userListener.onError(t);
            }
            
            // 失败也标记为完成，避免重复触发
            completed = true;
        }
        
        @Override
        public void onClosed(EventSource eventSource) {
            log.debug("SSE连接已关闭");
            // 不再这里触发onComplete，改为在收到[DONE]事件时触发
            // 如果没有收到[DONE]但连接关闭，才触发完成
            if (!completed) {
                completeEvent();
            }
        }
    }
    
    @Override
    public void sendToolResult(String promptId, String toolCallId, String result) throws IOException {
        if (config.isUseMcpProtocol()) {
            // 使用MCP协议发送工具结果
            String url = getFullServerPath() + "/tool-result";
            
            JsonObject requestBody = new JsonObject();
            requestBody.addProperty("id", promptId); // 提示ID
            requestBody.addProperty("tool_call_id", toolCallId); // 工具调用ID
            requestBody.addProperty("result", result); // 工具执行结果
            
            log.info("发送工具执行结果 (ID: {}): {}", toolCallId, result);
            
            Request request = new Request.Builder()
                    .url(url)
                    .post(RequestBody.create(requestBody.toString(), JSON))
                    .header("Authorization", "Bearer " + config.getApiKey())
                    .build();
            
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    log.error("发送工具结果失败，HTTP状态码: {}", response.code());
                    throw new IOException("发送工具结果失败: " + response.code());
                }
                
                log.debug("发送工具结果成功");
            }
        } else {
            // 直接API调用模式下的工具结果处理
            // 火山引擎可能不直接支持这种方式
            log.warn("当前模式不支持直接发送工具结果，结果将被丢弃: {}", result);
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
                    requestBody.addProperty("client_id", this.currentPromptId);
                    log.debug("未找到服务器分配的客户端ID，使用当前提示ID: {}", this.currentPromptId);
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
                                String error = "工具执行错误: " + resultObj.get("error").getAsString();
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
} 