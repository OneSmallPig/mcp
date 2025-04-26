package com.example.mcp.client.model;

/**
 * SSE事件类，表示从服务器收到的事件
 */
public class SseEvent {
    private String id;
    private SseEventType eventType;
    private String data;
    private long timestamp;

    public SseEvent() {
        this.timestamp = System.currentTimeMillis();
    }

    public SseEvent(SseEventType eventType, String data) {
        this.eventType = eventType;
        this.data = data;
        this.timestamp = System.currentTimeMillis();
    }

    public SseEvent(String id, SseEventType eventType, String data) {
        this.id = id;
        this.eventType = eventType;
        this.data = data;
        this.timestamp = System.currentTimeMillis();
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public SseEventType getEventType() {
        return eventType;
    }

    public void setEventType(SseEventType eventType) {
        this.eventType = eventType;
    }

    public String getData() {
        return data;
    }

    public void setData(String data) {
        this.data = data;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }
} 