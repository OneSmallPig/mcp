package com.example.mcp.client.util;

import com.example.mcp.client.config.McpClientConfig;
import com.example.mcp.client.model.Tool;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 工具获取器，用于从服务器API获取工具信息
 */
public class ToolFetcher {
    private static final Logger log = LoggerFactory.getLogger(ToolFetcher.class);
    private static final McpClientConfig config = McpClientConfig.getInstance();
    private static final Gson gson = new Gson();
    private static final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build();
    
    /**
     * 从服务器获取可用工具列表
     * 
     * @return 工具列表
     */
    public static List<Tool> fetchTools() {
        log.info("从服务器获取可用工具列表...");
        List<Tool> tools = new ArrayList<>();
        
        try {
            // 构建请求URL
            String baseUrl = config.getServerUrl();
            if (!baseUrl.endsWith("/")) {
                baseUrl += "/";
            }
            
            // 工具接口端点
            String toolsEndpoint = baseUrl + "api/tools";
            log.info("请求工具列表端点: {}", toolsEndpoint);
            
            // 构建请求
            Request request = new Request.Builder()
                    .url(toolsEndpoint)
                    .get()
                    .build();
            
            // 发送请求
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    log.error("获取工具列表失败，状态码: {}", response.code());
                    return tools;
                }
                
                String responseBody = response.body().string();
                log.debug("工具列表响应: {}", responseBody);
                
                // 解析工具信息
                JsonArray toolsArray = gson.fromJson(responseBody, JsonArray.class);
                for (JsonElement element : toolsArray) {
                    JsonObject toolObj = element.getAsJsonObject();
                    
                    Tool tool = new Tool();
                    tool.setName(toolObj.get("name").getAsString());
                    tool.setDescription(toolObj.get("description").getAsString());
                    
                    // 构建参数信息
                    Map<String, Object> parameters = buildParametersFromToolInfo(toolObj);
                    if (!parameters.isEmpty()) {
                        tool.setParameters(parameters);
                    }
                    
                    tools.add(tool);
                    log.info("发现工具: {} - {}", tool.getName(), tool.getDescription());
                }
            }
        } catch (IOException e) {
            log.error("获取工具列表时发生错误", e);
        }
        
        return tools;
    }
    
    /**
     * 从工具信息构建参数对象
     * 
     * @param toolObj 工具信息JSON对象
     * @return 参数映射
     */
    private static Map<String, Object> buildParametersFromToolInfo(JsonObject toolObj) {
        Map<String, Object> parameters = new HashMap<>();
        Map<String, Object> properties = new HashMap<>();
        List<String> required = new ArrayList<>();
        
        try {
            // 如果工具对象包含参数定义
            if (toolObj.has("parameters") && !toolObj.get("parameters").isJsonNull()) {
                JsonArray paramsArray = toolObj.getAsJsonArray("parameters");
                
                for (JsonElement paramElement : paramsArray) {
                    JsonObject paramObj = paramElement.getAsJsonObject();
                    String paramName = paramObj.get("name").getAsString();
                    String paramType = paramObj.has("type") ? paramObj.get("type").getAsString() : "string";
                    String description = paramObj.has("description") ? 
                            paramObj.get("description").getAsString() : paramName;
                    
                    Map<String, Object> paramInfo = new HashMap<>();
                    paramInfo.put("type", paramType);
                    paramInfo.put("description", description);
                    
                    properties.put(paramName, paramInfo);
                    
                    // 如果是必需参数
                    if (paramObj.has("required") && paramObj.get("required").getAsBoolean()) {
                        required.add(paramName);
                    }
                }
                
                if (!properties.isEmpty()) {
                    parameters.put("type", "object");
                    parameters.put("properties", properties);
                    if (!required.isEmpty()) {
                        parameters.put("required", required);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("解析工具参数时发生错误", e);
        }
        
        return parameters;
    }
    
    /**
     * 测试方法，用于验证工具获取功能
     */
    public static void main(String[] args) {
        String serverUrl = args.length > 0 ? args[0] : "http://localhost:8080";
        config.setServerUrl(serverUrl);
        
        System.out.println("=== 开始测试工具获取器 ===");
        System.out.println("连接服务器: " + serverUrl);
        
        List<Tool> tools = fetchTools();
        
        System.out.println("=== 获取结果 ===");
        System.out.println("共获取到 " + tools.size() + " 个工具:");
        
        for (Tool tool : tools) {
            System.out.println("\n工具名称: " + tool.getName());
            System.out.println("工具描述: " + tool.getDescription());
            if (tool.getParameters() != null && !tool.getParameters().isEmpty()) {
                System.out.println("工具参数: " + tool.getParameters());
            }
        }
        
        if (tools.isEmpty()) {
            System.out.println("未找到任何工具，请检查服务器地址和API是否正确。");
        }
    }
} 