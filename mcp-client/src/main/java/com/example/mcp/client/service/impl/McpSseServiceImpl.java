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
                .readTimeout(config.getTimeout() * 2, TimeUnit.SECONDS)
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
        
        // 确定请求URL
        String url;
        if (config.isUseMcpProtocol()) {
            // 使用MCP协议
            url = getFullServerPath();
            
            // 添加服务器期望的参数
            requestBody.addProperty("id", currentPromptId);
        } else {
            // 使用直接API调用
            url = config.getEndpoint();
        }
        
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
        
        public SseSourceListener(SseEventListener userListener) {
            this.userListener = userListener;
        }
        
        @Override
        public void onOpen(EventSource eventSource, Response response) {
            log.debug("SSE连接已打开: {}", response.code());
            // 通知连接已建立，使用onTextChunk发送空消息
            userListener.onTextChunk("");
        }
        
        @Override
        public void onEvent(EventSource eventSource, String id, String type, String data) {
            if (data == null || data.trim().isEmpty() || "[DONE]".equals(data.trim())) {
                log.debug("收到结束事件: {}", data);
                userListener.onComplete();
                return;
            }
            
            try {
                // 解析数据
                JsonObject jsonData = gson.fromJson(data, JsonObject.class);
                
                if (jsonData.has("choices") && !jsonData.get("choices").isJsonNull()) {
                    JsonObject deltaObj = jsonData.getAsJsonArray("choices")
                            .get(0).getAsJsonObject()
                            .getAsJsonObject("delta");
                    
                    if (deltaObj.has("content") && !deltaObj.get("content").isJsonNull()) {
                        String content = deltaObj.get("content").getAsString();
                        contentBuilder.append(content);
                        userListener.onTextChunk(content);
                    }
                }
            } catch (Exception e) {
                log.error("解析SSE事件数据时出错", e);
                userListener.onError(e);
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
        }
        
        @Override
        public void onClosed(EventSource eventSource) {
            log.debug("SSE连接已关闭");
            userListener.onComplete();
        }
    }
    
    @Override
    public void sendToolResult(String promptId, String toolCallId, String result) throws IOException {
        // 工具结果发送（如果需要的话）
        // 在本实现中简化处理
        log.debug("工具结果发送功能暂未实现");
    }
    
    @Override
    public void close() {
        if (currentEventSource != null) {
            try {
                currentEventSource.close();
                currentEventSource = null;
            } catch (Exception e) {
                log.warn("关闭SSE连接时出错", e);
            }
        }
        log.info("已关闭MCP SSE服务");
    }
} 