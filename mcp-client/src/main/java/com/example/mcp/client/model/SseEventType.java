package com.example.mcp.client.model;

/**
 * SSE事件类型枚举
 */
public enum SseEventType {
    /**
     * 模型生成的文本片段
     */
    TEXT_CHUNK,
    
    /**
     * 模型请求调用工具
     */
    TOOL_CALL,
    
    /**
     * 工具执行结果
     */
    TOOL_RESULT,
    
    /**
     * 交互完成
     */
    FINISHED,
    
    /**
     * 发生错误
     */
    ERROR
} 