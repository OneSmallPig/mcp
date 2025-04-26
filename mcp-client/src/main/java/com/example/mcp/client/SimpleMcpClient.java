package com.example.mcp.client;

import com.example.mcp.client.model.ToolCall;
import com.example.mcp.client.service.McpSseService;
import com.example.mcp.client.service.McpSseServiceImpl;
import com.example.mcp.client.service.SimpleSseEventListener;
import com.google.gson.Gson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * 简化的MCP客户端，专为初学者设计
 * 提供简单易用的API，隐藏复杂的实现细节
 */
public class SimpleMcpClient {
    private static final Logger log = LoggerFactory.getLogger(SimpleMcpClient.class);
    private final McpSseService sseService;
    private final Gson gson;
    
    /**
     * 创建简化客户端
     */
    public SimpleMcpClient() {
        this.sseService = new McpSseServiceImpl();
        this.gson = new Gson();
    }
    
    /**
     * 发送消息并获取AI回复
     * 
     * @param userMessage 用户消息
     * @param callback 回调接口，用于接收响应
     */
    public void chat(String userMessage, ChatCallback callback) {
        try {
            log.info("发送用户消息: {}", userMessage);
            
            sseService.sendPrompt(userMessage, new SimpleSseEventListener() {
                @Override
                public void onTextChunk(String text) {
                    callback.onAiResponse(text);
                }
                
                @Override
                public void onToolCall(ToolCall toolCall) {
                    callback.onToolUse(toolCall.getFunction(), 
                                     gson.toJson(toolCall.getArguments()));
                }
                
                @Override
                public void onToolResult(String function, String result) {
                    callback.onToolResult(function, result);
                }
                
                @Override
                public void onComplete() {
                    callback.onFinished();
                }
                
                @Override
                public void onError(Throwable t) {
                    log.error("聊天错误", t);
                    callback.onError(t.getMessage());
                }
            });
        } catch (IOException e) {
            log.error("发送请求失败", e);
            callback.onError("发送请求失败: " + e.getMessage());
        }
    }
    
    /**
     * 关闭客户端
     */
    public void close() {
        sseService.close();
    }
    
    /**
     * 聊天回调接口
     */
    public interface ChatCallback {
        /**
         * 接收AI实时回复
         * 
         * @param text 文本片段
         */
        void onAiResponse(String text);
        
        /**
         * AI使用工具时调用
         * 
         * @param toolName 工具名称
         * @param parameters 参数JSON
         */
        void onToolUse(String toolName, String parameters);
        
        /**
         * 工具返回结果时调用
         * 
         * @param toolName 工具名称
         * @param result 结果
         */
        void onToolResult(String toolName, String result);
        
        /**
         * 对话完成时调用
         */
        void onFinished();
        
        /**
         * 发生错误时调用
         * 
         * @param errorMessage 错误信息
         */
        void onError(String errorMessage);
    }
} 