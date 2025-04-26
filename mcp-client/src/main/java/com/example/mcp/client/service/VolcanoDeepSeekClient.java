package com.example.mcp.client.service;

import com.example.mcp.client.config.McpClientConfig;
import com.example.mcp.client.model.ChatRequest;
import com.example.mcp.client.model.ChatResponse;
import com.google.gson.Gson;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * 火山引擎DeepSeek服务客户端
 */
public class VolcanoDeepSeekClient {
    private static final Logger log = LoggerFactory.getLogger(VolcanoDeepSeekClient.class);
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    
    private final OkHttpClient httpClient;
    private final Gson gson;
    private final McpClientConfig config;
    
    public VolcanoDeepSeekClient() {
        this.config = McpClientConfig.getInstance();
        this.config.overrideWithEnvVars();
        
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(config.getTimeout(), TimeUnit.SECONDS)
                .readTimeout(config.getTimeout(), TimeUnit.SECONDS)
                .writeTimeout(config.getTimeout(), TimeUnit.SECONDS)
                .build();
        
        this.gson = new Gson();
    }
    
    /**
     * 发送聊天请求到火山引擎DeepSeek服务
     * 
     * @param request 聊天请求对象
     * @return 聊天响应对象
     * @throws IOException 请求失败时抛出
     */
    public ChatResponse chat(ChatRequest request) throws IOException {
        // 设置模型
        if (request.getModel() == null || request.getModel().isEmpty()) {
            request.setModel(config.getModel());
        }
        
        // 转换请求为JSON
        String jsonBody = gson.toJson(request);
        
        // 构建请求
        Request httpRequest = buildRequest(jsonBody);
        
        // 发送请求
        try (Response response = httpClient.newCall(httpRequest).execute()) {
            // 检查响应
            if (!response.isSuccessful()) {
                String error = response.body() != null ? response.body().string() : "Unknown error";
                log.error("请求失败: {}", error);
                throw new IOException("请求失败: " + response.code() + " " + error);
            }
            
            // 解析响应
            if (response.body() != null) {
                String responseBody = response.body().string();
                log.debug("收到响应: {}", responseBody);
                return gson.fromJson(responseBody, ChatResponse.class);
            } else {
                throw new IOException("响应体为空");
            }
        }
    }
    
    /**
     * 构建HTTP请求，使用Bearer Token认证方式
     * 
     * @param jsonBody 请求体JSON字符串
     * @return OkHttp请求对象
     */
    private Request buildRequest(String jsonBody) {
        try {
            // 构建请求，使用Bearer Token认证方式
            return new Request.Builder()
                    .url(config.getEndpoint())
                    .post(RequestBody.create(jsonBody, JSON))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + config.getApiKey())
                    .build();
        } catch (Exception e) {
            log.error("构建请求失败", e);
            throw new RuntimeException("构建请求失败", e);
        }
    }
    
    /**
     * 简单的重试机制
     * 
     * @param retryCount 当前重试次数
     * @param e 异常信息
     * @return 是否应该重试
     */
    private boolean shouldRetry(int retryCount, Exception e) {
        if (retryCount >= config.getRetries()) {
            return false;
        }
        
        // 只针对网络错误重试，不针对API错误
        return e instanceof IOException && !(e instanceof okhttp3.internal.http2.StreamResetException);
    }
} 