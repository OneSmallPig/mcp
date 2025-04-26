package com.example.mcp.client.service;

import com.example.mcp.client.model.ToolCall;

/**
 * 简单的SSE事件监听器实现类，提供空实现，便于扩展
 */
public class SimpleSseEventListener implements SseEventListener {
    
    @Override
    public void onTextChunk(String text) {
        // 默认空实现
    }
    
    @Override
    public void onToolCall(ToolCall toolCall) {
        // 默认空实现
    }
    
    @Override
    public void onToolResult(String function, String result) {
        // 默认空实现
    }
    
    @Override
    public void onComplete() {
        // 默认空实现
    }
    
    @Override
    public void onError(Throwable t) {
        t.printStackTrace();
    }
} 