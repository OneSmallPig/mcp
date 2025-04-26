package com.example.mcp.client.sse;

import okhttp3.Response;

/**
 * 事件源监听器
 */
public abstract class EventSourceListener {
    /**
     * 当连接打开时调用
     *
     * @param eventSource 事件源
     * @param response 响应
     */
    public void onOpen(EventSource eventSource, Response response) {
    }

    /**
     * 当收到事件时调用
     *
     * @param eventSource 事件源
     * @param id 事件ID
     * @param type 事件类型
     * @param data 事件数据
     */
    public void onEvent(EventSource eventSource, String id, String type, String data) {
    }

    /**
     * 当连接关闭时调用
     *
     * @param eventSource 事件源
     */
    public void onClosed(EventSource eventSource) {
    }

    /**
     * 当连接失败时调用
     *
     * @param eventSource 事件源
     * @param t 异常
     * @param response 响应
     */
    public void onFailure(EventSource eventSource, Throwable t, Response response) {
    }
} 