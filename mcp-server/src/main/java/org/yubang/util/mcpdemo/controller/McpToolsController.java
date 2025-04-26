package org.yubang.util.mcpdemo.controller;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.http.ResponseEntity;
import org.yubang.util.mcpdemo.model.ToolInfo;
import org.yubang.util.mcpdemo.service.ApiService;
import org.yubang.util.mcpdemo.service.DatabaseService;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.lang.reflect.InvocationTargetException;
import com.google.gson.Gson;
import org.yubang.util.mcpdemo.service.ExcelExportService;

/**
 * MCP工具信息控制器
 * 提供直接获取MCP工具信息的API
 */
@RestController
@RequestMapping("/api/mcp")
public class McpToolsController {
    private static final Logger log = LoggerFactory.getLogger(McpToolsController.class);

    private final ApiService apiService;
    private final DatabaseService databaseService;
    private final ExcelExportService excelExportService;

    @Autowired
    public McpToolsController(ApiService apiService, DatabaseService databaseService, ExcelExportService excelExportService) {
        this.apiService = apiService;
        this.databaseService = databaseService;
        this.excelExportService = excelExportService;
    }

    /**
     * 获取MCP工具列表
     * @return MCP工具信息
     */
    @GetMapping("/tools")
    public List<Map<String, Object>> getToolList() {
        List<Map<String, Object>> tools = new ArrayList<>();
        
        // 扫描工具服务
        Object[] services = {apiService, databaseService, excelExportService};
        
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
        
        Object[] services = {apiService, databaseService, excelExportService};
        
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

    /**
     * 执行MCP工具
     * @param requestMap 请求参数，包含function、arguments
     * @return 执行结果
     */
    @PostMapping("/execute")
    public ResponseEntity<Map<String, Object>> executeTools(@RequestBody Map<String, Object> requestMap) {
        log.info("收到工具执行请求: {}", requestMap);
        
        // 提取执行参数
        String functionName = (String) requestMap.get("function");
        Map<String, Object> arguments = (Map<String, Object>) requestMap.get("arguments");
        String clientId = (String) requestMap.getOrDefault("client_id", "");
        
        if (functionName == null || functionName.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "缺少function参数"
            ));
        }
        
        log.info("准备执行工具: {}，参数: {}", functionName, arguments);
        
        // 查找工具函数
        Object targetService = null;
        Method targetMethod = null;
        
        for (Object service : new Object[]{apiService, databaseService}) {
            for (Method method : service.getClass().getMethods()) {
                if (method.isAnnotationPresent(org.springframework.ai.tool.annotation.Tool.class)) {
                    org.springframework.ai.tool.annotation.Tool annotation = 
                        method.getAnnotation(org.springframework.ai.tool.annotation.Tool.class);
                    
                    if (annotation.name().equals(functionName)) {
                        targetService = service;
                        targetMethod = method;
                        break;
                    }
                }
            }
            if (targetMethod != null) break;
        }
        
        if (targetMethod == null || targetService == null) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "找不到工具: " + functionName
            ));
        }
        
        // 执行工具函数
        try {
            // 准备参数
            Object[] methodArgs = prepareMethodArguments(targetMethod, arguments);
            
            // 调用方法
            Object result = targetMethod.invoke(targetService, methodArgs);
            
            // 构建响应
            Map<String, Object> response = new HashMap<>();
            response.put("function", functionName);
            response.put("result", result != null ? result.toString() : "");
            
            if (!clientId.isEmpty()) {
                response.put("client_id", clientId);
            }
            
            log.info("工具执行成功: {}，结果: {}", functionName, result);
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            log.error("执行工具失败", e);
            Throwable cause = e instanceof InvocationTargetException ? 
                ((InvocationTargetException) e).getTargetException() : e;
                
            return ResponseEntity.badRequest().body(Map.of(
                "error", "执行工具失败: " + cause.getMessage(),
                "function", functionName
            ));
        }
    }
    
    /**
     * 准备方法调用参数
     * 
     * @param method 目标方法
     * @param arguments 参数值映射
     * @return 参数数组
     */
    private Object[] prepareMethodArguments(Method method, Map<String, Object> arguments) {
        if (arguments == null) {
            arguments = Map.of();
        }
        
        java.lang.reflect.Parameter[] parameters = method.getParameters();
        Object[] args = new Object[parameters.length];
        Gson gson = new Gson();
        
        for (int i = 0; i < parameters.length; i++) {
            java.lang.reflect.Parameter param = parameters[i];
            String paramName = param.getName();
            Class<?> paramType = param.getType();
            
            if (arguments.containsKey(paramName)) {
                Object value = arguments.get(paramName);
                
                // 基本类型直接使用
                if (paramType.isPrimitive() || 
                    paramType.equals(String.class) || 
                    Number.class.isAssignableFrom(paramType) ||
                    Boolean.class.equals(paramType)) {
                    args[i] = value;
                } 
                // 复杂类型需要转换
                else {
                    String json = gson.toJson(value);
                    args[i] = gson.fromJson(json, paramType);
                }
            } else {
                // 参数不存在，使用默认值
                if (paramType.isPrimitive()) {
                    // 为基本类型提供默认值
                    if (paramType == int.class || paramType == long.class || 
                        paramType == short.class || paramType == byte.class) {
                        args[i] = 0;
                    } else if (paramType == double.class || paramType == float.class) {
                        args[i] = 0.0;
                    } else if (paramType == boolean.class) {
                        args[i] = false;
                    } else if (paramType == char.class) {
                        args[i] = '\u0000';
                    }
                } else {
                    args[i] = null;
                }
            }
        }
        
        return args;
    }
} 