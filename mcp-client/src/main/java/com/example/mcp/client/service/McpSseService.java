package com.example.mcp.client.service;

import com.example.mcp.client.model.Message;

import java.io.IOException;
import java.util.List;

/**
 * MCP SSE服务接口
 */
public interface McpSseService {
    
    /**
     * 发送用户输入并处理SSE响应
     * 
     * @param prompt 用户输入
     * @param listener 事件监听器
     */
    void sendPrompt(String prompt, SseEventListener listener) throws IOException;
    
    /**
     * 发送历史消息和用户输入并处理SSE响应
     * 
     * @param history 历史消息
     * @param prompt 用户输入
     * @param listener 事件监听器
     */
    void sendPrompt(List<Message> history, String prompt, SseEventListener listener) throws IOException;

    /**
     * 关闭连接
     */
    void close();
} 