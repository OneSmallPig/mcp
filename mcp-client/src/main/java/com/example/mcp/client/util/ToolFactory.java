package com.example.mcp.client.util;

import com.example.mcp.client.model.Tool;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具工厂类，用于创建Tool对象
 */
public class ToolFactory {
    
    /**
     * 创建一个简单的工具对象
     * 
     * @param name 工具名称
     * @param description 工具描述
     * @return 工具对象
     */
    public static Tool createSimpleTool(String name, String description) {
        Tool tool = new Tool();
        tool.setName(name);
        tool.setDescription(description);
        return tool;
    }
    
    /**
     * 创建带参数的工具对象
     * 
     * @param name 工具名称
     * @param description 工具描述
     * @param parameters 参数映射
     * @param requiredParams 必需参数列表
     * @return 工具对象
     */
    public static Tool createTool(String name, String description, Map<String, Object> parameters, List<String> requiredParams) {
        Tool tool = new Tool();
        tool.setName(name);
        tool.setDescription(description);
        tool.setParameters(parameters);
        tool.setRequiredParameters(requiredParams);
        return tool;
    }
    
    /**
     * 创建包含单个字符串参数的工具对象
     * 
     * @param name 工具名称
     * @param description 工具描述
     * @param paramName 参数名称
     * @param paramDescription 参数描述
     * @param required 是否必需
     * @return 工具对象
     */
    public static Tool createStringParamTool(String name, String description, String paramName, String paramDescription, boolean required) {
        Tool tool = new Tool();
        tool.setName(name);
        tool.setDescription(description);
        
        Map<String, Object> parameters = new HashMap<>();
        Map<String, Object> properties = new HashMap<>();
        
        Map<String, Object> paramInfo = new HashMap<>();
        paramInfo.put("type", "string");
        paramInfo.put("description", paramDescription);
        
        properties.put(paramName, paramInfo);
        
        parameters.put("type", "object");
        parameters.put("properties", properties);
        
        if (required) {
            List<String> requiredParams = new ArrayList<>();
            requiredParams.add(paramName);
            parameters.put("required", requiredParams);
            tool.setRequiredParameters(requiredParams);
        }
        
        tool.setParameters(parameters);
        return tool;
    }
    
    /**
     * 创建带有多个参数的工具对象构建器
     */
    public static class ToolBuilder {
        private String name;
        private String description;
        private Map<String, Object> parameters = new HashMap<>();
        private Map<String, Object> properties = new HashMap<>();
        private List<String> required = new ArrayList<>();
        
        public ToolBuilder(String name, String description) {
            this.name = name;
            this.description = description;
        }
        
        public ToolBuilder addStringParam(String name, String description, boolean isRequired) {
            Map<String, Object> param = new HashMap<>();
            param.put("type", "string");
            param.put("description", description);
            
            properties.put(name, param);
            if (isRequired) {
                required.add(name);
            }
            
            return this;
        }
        
        public ToolBuilder addNumberParam(String name, String description, boolean isRequired) {
            Map<String, Object> param = new HashMap<>();
            param.put("type", "number");
            param.put("description", description);
            
            properties.put(name, param);
            if (isRequired) {
                required.add(name);
            }
            
            return this;
        }
        
        public ToolBuilder addBooleanParam(String name, String description, boolean isRequired) {
            Map<String, Object> param = new HashMap<>();
            param.put("type", "boolean");
            param.put("description", description);
            
            properties.put(name, param);
            if (isRequired) {
                required.add(name);
            }
            
            return this;
        }
        
        public Tool build() {
            Tool tool = new Tool();
            tool.setName(name);
            tool.setDescription(description);
            
            parameters.put("type", "object");
            parameters.put("properties", properties);
            
            if (!required.isEmpty()) {
                parameters.put("required", required);
                tool.setRequiredParameters(required);
            }
            
            tool.setParameters(parameters);
            return tool;
        }
    }
    
    /**
     * 创建工具构建器
     * 
     * @param name 工具名称
     * @param description 工具描述
     * @return 工具构建器
     */
    public static ToolBuilder builder(String name, String description) {
        return new ToolBuilder(name, description);
    }
} 