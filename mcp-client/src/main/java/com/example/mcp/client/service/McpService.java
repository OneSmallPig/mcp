package com.example.mcp.client.service;

import com.example.mcp.client.model.ChatRequest;
import com.example.mcp.client.model.ChatResponse;

import java.io.IOException;

/**
 * MCP服务接口
 */
public interface McpService {
    
    /**
     * 发送聊天请求
     * 
     * @param request 聊天请求
     * @return 聊天响应
     * @throws IOException 请求失败时抛出
     */
    ChatResponse sendChatRequest(ChatRequest request) throws IOException;
} 