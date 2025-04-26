package com.example.mcp.client.sse;

import java.io.Closeable;
import okhttp3.Request;

/**
 * 事件源接口
 * 定义SSE连接的基本操作
 */
public interface EventSource extends Closeable {

    /**
     * 请求并建立SSE连接
     */
    void connect();

    /**
     * 取消SSE连接
     */
    void cancel();
    
    /**
     * 检查连接是否已关闭
     * 
     * @return 如果连接已关闭，返回true；否则返回false
     */
    boolean isClosed();

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