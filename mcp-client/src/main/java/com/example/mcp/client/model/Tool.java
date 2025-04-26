package com.example.mcp.client.model;

import java.util.List;
import java.util.Map;

/**
 * MCP工具模型类，用于表示MCP服务器提供的工具
 */
public class Tool {
    private String name;
    private String description;
    private Map<String, Object> parameters;
    private boolean required;
    
    public Tool() {
    }
    
    public Tool(String name, String description, Map<String, Object> parameters, boolean required) {
        this.name = name;
        this.description = description;
        this.parameters = parameters;
        this.required = required;
    }
    
    public String getName() {
        return name;
    }
    
    public void setName(String name) {
        this.name = name;
    }
    
    public String getDescription() {
        return description;
    }
    
    public void setDescription(String description) {
        this.description = description;
    }
    
    public Map<String, Object> getParameters() {
        return parameters;
    }
    
    public void setParameters(Map<String, Object> parameters) {
        this.parameters = parameters;
    }
    
    public boolean isRequired() {
        return required;
    }
    
    public void setRequired(boolean required) {
        this.required = required;
    }
    
    @Override
    public String toString() {
        return "Tool{" +
                "name='" + name + '\'' +
                ", description='" + description + '\'' +
                ", parameters=" + parameters +
                ", required=" + required +
                '}';
    }
} 