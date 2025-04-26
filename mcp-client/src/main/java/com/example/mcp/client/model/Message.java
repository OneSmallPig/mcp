package com.example.mcp.client.model;

import java.util.List;

/**
 * 聊天消息模型
 */
public class Message {
    private String role;
    private String content;
    private List<ToolCall> toolCalls;
    private String toolCallId;

    public Message() {
    }

    public Message(String role, String content) {
        this.role = role;
        this.content = content;
    }
    
    public Message(String role, String content, List<ToolCall> toolCalls) {
        this.role = role;
        this.content = content;
        this.toolCalls = toolCalls;
    }
    
    public Message(String role, String content, String toolCallId) {
        this.role = role;
        this.content = content;
        this.toolCallId = toolCallId;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }
    
    public List<ToolCall> getToolCalls() {
        return toolCalls;
    }
    
    public void setToolCalls(List<ToolCall> toolCalls) {
        this.toolCalls = toolCalls;
    }
    
    public String getToolCallId() {
        return toolCallId;
    }
    
    public void setToolCallId(String toolCallId) {
        this.toolCallId = toolCallId;
    }
} 