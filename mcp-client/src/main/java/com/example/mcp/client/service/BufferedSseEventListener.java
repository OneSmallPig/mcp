package com.example.mcp.client.service;

import com.example.mcp.client.model.ToolCall;

/**
 * 带缓冲的SSE事件监听器，收集所有响应但不自动输出
 */
public class BufferedSseEventListener extends SimpleSseEventListener {
    
    private final StringBuilder responseBuilder = new StringBuilder();
    private boolean hasToolCalls = false;
    
    /**
     * 接收文本片段但不立即输出，而是缓存起来
     */
    @Override
    public void onTextChunk(String text) {
        responseBuilder.append(text);
    }
    
    /**
     * 处理工具调用
     */
    @Override
    public void onToolCall(ToolCall toolCall) {
        hasToolCalls = true;
        // 可以在这里收集工具调用信息，但默认实现是直接透传给父类
        super.onToolCall(toolCall);
    }
    
    /**
     * 完成时不再自动输出，由调用者负责处理完整响应
     */
    @Override
    public void onComplete() {
        // 调用父类方法以支持额外处理，但不自动打印响应
        super.onComplete();
    }
    
    /**
     * 获取完整响应文本
     */
    public String getCompleteResponse() {
        return responseBuilder.toString();
    }
    
    /**
     * 清空响应缓冲
     */
    public void clearResponse() {
        responseBuilder.setLength(0);
        hasToolCalls = false;
    }
} 