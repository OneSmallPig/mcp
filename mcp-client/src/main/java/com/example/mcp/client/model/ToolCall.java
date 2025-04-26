package com.example.mcp.client.model;

import java.util.Map;

/**
 * 工具调用模型类，用于表示对MCP工具的调用
 */
public class ToolCall {
    private String id;
    private String type;
    private String function;
    private Map<String, Object> arguments;
    private String result;
    
    public ToolCall() {
    }
    
    public ToolCall(String id, String type, String function, Map<String, Object> arguments) {
        this.id = id;
        this.type = type;
        this.function = function;
        this.arguments = arguments;
    }
    
    public String getId() {
        return id;
    }
    
    public void setId(String id) {
        this.id = id;
    }
    
    public String getType() {
        return type;
    }
    
    public void setType(String type) {
        this.type = type;
    }
    
    public String getFunction() {
        return function;
    }
    
    public void setFunction(String function) {
        this.function = function;
    }
    
    public Map<String, Object> getArguments() {
        return arguments;
    }
    
    public void setArguments(Map<String, Object> arguments) {
        this.arguments = arguments;
    }
    
    public String getResult() {
        return result;
    }
    
    public void setResult(String result) {
        this.result = result;
    }
    
    @Override
    public String toString() {
        return "ToolCall{" +
                "id='" + id + '\'' +
                ", type='" + type + '\'' +
                ", function='" + function + '\'' +
                ", arguments=" + arguments +
                ", result='" + result + '\'' +
                '}';
    }
} 