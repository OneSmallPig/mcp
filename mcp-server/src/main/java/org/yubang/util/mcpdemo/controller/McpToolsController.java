package org.yubang.util.mcpdemo.controller;


import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.yubang.util.mcpdemo.model.ToolInfo;
import org.yubang.util.mcpdemo.service.ApiService;
import org.yubang.util.mcpdemo.service.DatabaseService;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP工具信息控制器
 * 提供直接获取MCP工具信息的API
 */
@RestController
@RequestMapping("/api/mcp")
public class McpToolsController {

    private final ApiService apiService;
    private final DatabaseService databaseService;

    public McpToolsController(ApiService apiService, DatabaseService databaseService) {
        this.apiService = apiService;
        this.databaseService = databaseService;
    }

    /**
     * 获取MCP工具列表
     * @return MCP工具信息
     */
    @GetMapping("/tools")
    public List<Map<String, Object>> getToolList() {
        List<Map<String, Object>> tools = new ArrayList<>();
        
        // 扫描工具服务
        Object[] services = {apiService, databaseService};
        
        for (Object service : services) {
            for (Method method : service.getClass().getMethods()) {
                if (method.isAnnotationPresent(org.springframework.ai.tool.annotation.Tool.class)) {
                    org.springframework.ai.tool.annotation.Tool annotation = 
                        method.getAnnotation(org.springframework.ai.tool.annotation.Tool.class);
                    
                    Map<String, Object> toolInfo = new HashMap<>();
                    toolInfo.put("name", annotation.name());
                    toolInfo.put("description", annotation.description());
                    toolInfo.put("methodName", method.getName());
                    toolInfo.put("serviceName", service.getClass().getSimpleName());
                    
                    // 解析参数信息
                    Map<String, Object> parameters = new HashMap<>();
                    for (java.lang.reflect.Parameter param : method.getParameters()) {
                        Map<String, Object> paramInfo = new HashMap<>();
                        paramInfo.put("type", param.getType().getSimpleName());
                        paramInfo.put("description", param.getName()); // 简化处理，使用参数名作为描述
                        
                        parameters.put(param.getName(), paramInfo);
                    }
                    toolInfo.put("parameters", parameters);
                    
                    tools.add(toolInfo);
                }
            }
        }
        
        return tools;
    }
    
    /**
     * 获取简化版MCP工具信息
     * @return 简化的工具信息列表
     */
    @GetMapping("/tools/simple")
    public List<ToolInfo> getSimpleToolList() {
        List<ToolInfo> toolInfoList = new ArrayList<>();
        
        Object[] services = {apiService, databaseService};
        
        for (Object service : services) {
            for (Method method : service.getClass().getMethods()) {
                if (method.isAnnotationPresent(org.springframework.ai.tool.annotation.Tool.class)) {
                    org.springframework.ai.tool.annotation.Tool annotation = 
                        method.getAnnotation(org.springframework.ai.tool.annotation.Tool.class);
                    
                    ToolInfo toolInfo = new ToolInfo();
                    toolInfo.setName(annotation.name());
                    toolInfo.setDescription(annotation.description());
                    toolInfo.setMethodName(method.getName());
                    toolInfo.setClassName(service.getClass().getSimpleName());
                    
                    toolInfoList.add(toolInfo);
                }
            }
        }
        
        return toolInfoList;
    }
} 