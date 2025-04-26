package com.example.mcp.client.model;

import java.util.List;
import java.util.Map;

/**
 * 聊天请求模型
 */
public class ChatRequest {
    private String model;
    private List<Message> messages;
    private double temperature;
    private int maxTokens;
    private boolean stream;
    private Map<String, Object> parameters;
    private List<Tool> tools;
    private List<ToolCall> toolCalls;
    private String toolChoice;

    public ChatRequest() {
        this.temperature = 0.7;
        this.maxTokens = 2048;
        this.stream = false;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public List<Message> getMessages() {
        return messages;
    }

    public void setMessages(List<Message> messages) {
        this.messages = messages;
    }

    public double getTemperature() {
        return temperature;
    }

    public void setTemperature(double temperature) {
        this.temperature = temperature;
    }

    public int getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(int maxTokens) {
        this.maxTokens = maxTokens;
    }

    public boolean isStream() {
        return stream;
    }

    public void setStream(boolean stream) {
        this.stream = stream;
    }

    public Map<String, Object> getParameters() {
        return parameters;
    }

    public void setParameters(Map<String, Object> parameters) {
        this.parameters = parameters;
    }
    
    public List<Tool> getTools() {
        return tools;
    }
    
    public void setTools(List<Tool> tools) {
        this.tools = tools;
    }
    
    public List<ToolCall> getToolCalls() {
        return toolCalls;
    }
    
    public void setToolCalls(List<ToolCall> toolCalls) {
        this.toolCalls = toolCalls;
    }
    
    public String getToolChoice() {
        return toolChoice;
    }
    
    public void setToolChoice(String toolChoice) {
        this.toolChoice = toolChoice;
    }
} 