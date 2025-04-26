package com.example.mcp.client.sse;

import okhttp3.Request;

/**
 * 简单的EventSource接口
 */
public interface EventSource {
    /**
     * 取消连接
     */
    void cancel();
    
    /**
     * 工厂接口，用于创建EventSource
     */
    interface Factory {
        /**
         * 创建新的EventSource
         * 
         * @param request HTTP请求
         * @param listener 事件监听器
         * @return 新的EventSource实例
         */
        EventSource newEventSource(Request request, EventSourceListener listener);
    }
} 