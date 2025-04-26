package com.example.mcp.client.service;

import com.example.mcp.client.model.SseEvent;
import com.example.mcp.client.model.ToolCall;

/**
 * SSE事件监听器接口
 */
public interface SseEventListener {
    
    /**
     * 处理文本片段事件
     * 
     * @param text 模型生成的文本片段
     */
    void onTextChunk(String text);
    
    /**
     * 处理工具调用事件
     * 
     * @param toolCall 工具调用对象
     */
    void onToolCall(ToolCall toolCall);
    
    /**
     * 处理工具结果事件
     * 
     * @param function 工具名称
     * @param result 工具执行结果
     */
    void onToolResult(String function, String result);
    
    /**
     * 处理交互完成事件
     */
    void onComplete();
    
    /**
     * 处理错误事件
     * 
     * @param t 错误信息
     */
    void onError(Throwable t);
} 