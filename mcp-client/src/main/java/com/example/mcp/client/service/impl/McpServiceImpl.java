package com.example.mcp.client.service.impl;

import com.example.mcp.client.config.McpClientConfig;
import com.example.mcp.client.model.ChatRequest;
import com.example.mcp.client.model.ChatResponse;
import com.example.mcp.client.model.Message;
import com.example.mcp.client.model.Tool;
import com.example.mcp.client.model.ToolCall;
import com.example.mcp.client.service.McpService;
import com.example.mcp.client.service.VolcanoDeepSeekClient;
import com.example.mcp.client.util.JarToolScanner;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.lang.reflect.Type;
import java.net.Socket;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * MCP服务实现
 */
public class McpServiceImpl implements McpService {
    private static final Logger log = LoggerFactory.getLogger(McpServiceImpl.class);
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    
    private final VolcanoDeepSeekClient volcanoClient;
    private final OkHttpClient httpClient;
    private final Gson gson;
    private final McpClientConfig config;
    private Process mcpServerProcess;
    private boolean serverStarted = false;
    
    public McpServiceImpl() {
        this.volcanoClient = new VolcanoDeepSeekClient();
        this.config = McpClientConfig.getInstance();
        
        // 打印关键配置信息，帮助调试
        log.info("MCP客户端配置状态:");
        log.info("API密钥: {}", config.getApiKey() != null ? "已配置" : "未配置");
        log.info("端点: {}", config.getEndpoint());
        log.info("模型: {}", config.getModel());
        log.info("服务器URL: {}", config.getServerUrl());
        log.info("服务器路径: {}", config.getServerPath());
        log.info("本地服务器命令: {}", config.getServerCommand());
        log.info("本地服务器参数: {}", config.getServerArgs() != null ? Arrays.toString(config.getServerArgs()) : "无");
        log.info("使用本地服务器: {}", config.isUseLocalServer());
        log.info("工具数量: {}", config.getTools().size());
        
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(config.getTimeout(), TimeUnit.SECONDS)
                .readTimeout(config.getTimeout(), TimeUnit.SECONDS)
                .writeTimeout(config.getTimeout(), TimeUnit.SECONDS)
                .build();
        
        this.gson = new Gson();
        
        // 首先判断是否为JAR形式的服务器
        boolean isJarServer = isJarServer();
        log.info("服务器类型检测: {}", isJarServer ? "JAR形式服务器" : "非JAR形式服务器");
        
        // 如果配置了本地服务器，则启动它
        if (config.isUseLocalServer() && config.getServerCommand() != null) {
            log.info("准备启动本地服务器: {} {}", 
                    config.getServerCommand(), 
                    config.getServerArgs() != null ? Arrays.toString(config.getServerArgs()) : "");
            
            startLocalServer();
            
            // 如果服务器已成功启动，为本地服务器设置默认URL（如果未配置）
            if (serverStarted) {
                config.setDefaultLocalServerUrl();
                // 通过HTTP接口从已启动的服务器获取工具列表
                fetchToolsFromRunningServer();
            }
        } else {
            log.info("本地服务器配置检查: useLocalServer={}, serverCommand={}", 
                    config.isUseLocalServer(), config.getServerCommand());
                
            // 尝试获取远程MCP服务器提供的工具列表
            if (config.getServerUrl() != null && !config.getServerUrl().isEmpty()) {
                fetchToolsFromMcpServer();
            }
        }
    }
    
    /**
     * 启动本地MCP服务器
     */
    private void startLocalServer() {
        try {
            if (mcpServerProcess != null && mcpServerProcess.isAlive()) {
                log.info("MCP服务器已经在运行中");
                serverStarted = true;
                return;
            }
            
            log.info("正在启动本地MCP服务器...");
            log.info("命令: {}", config.getServerCommand());
            if (config.getServerArgs() != null && config.getServerArgs().length > 0) {
                log.info("参数: {}", Arrays.toString(config.getServerArgs()));
            }
            
            // 构建命令
            ProcessBuilder processBuilder = new ProcessBuilder();
            if (config.getServerArgs() != null && config.getServerArgs().length > 0) {
                String[] command = new String[config.getServerArgs().length + 1];
                command[0] = config.getServerCommand();
                System.arraycopy(config.getServerArgs(), 0, command, 1, config.getServerArgs().length);
                processBuilder.command(command);
                log.info("完整命令: {}", Arrays.toString(command));
            } else {
                processBuilder.command(config.getServerCommand());
                log.info("完整命令: {}", config.getServerCommand());
            }
            
            // 启动进程
            mcpServerProcess = processBuilder.start();
            
            // 启动日志收集线程
            startLogCollector();
            
            // 等待服务器启动
            log.info("等待MCP服务器启动...");
            Thread.sleep(5000);  // 简单等待5秒
            
            if (mcpServerProcess.isAlive()) {
                serverStarted = true;
                log.info("MCP服务器已启动");
            } else {
                int exitCode = mcpServerProcess.exitValue();
                log.error("MCP服务器启动失败，退出代码: {}", exitCode);
                serverStarted = false;
            }
            
            // 添加关闭钩子，在JVM退出时关闭服务器
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                if (mcpServerProcess != null && mcpServerProcess.isAlive()) {
                    log.info("关闭MCP服务器...");
                    mcpServerProcess.destroy();
                    try {
                        // 等待服务器优雅关闭
                        if (!mcpServerProcess.waitFor(10, TimeUnit.SECONDS)) {
                            mcpServerProcess.destroyForcibly();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }));
        } catch (Exception e) {
            log.error("启动本地MCP服务器失败", e);
            if (mcpServerProcess != null) {
                mcpServerProcess.destroyForcibly();
            }
            serverStarted = false;
        }
    }
    
    /**
     * 启动日志收集线程，将服务器的输出记录到日志
     */
    private void startLogCollector() {
        // 收集标准输出
        new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(mcpServerProcess.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.info("[MCP Server] {}", line);
                }
            } catch (IOException e) {
                log.error("读取MCP服务器标准输出失败", e);
            }
        }).start();
        
        // 收集错误输出
        new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(mcpServerProcess.getErrorStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.error("[MCP Server] {}", line);
                }
            } catch (IOException e) {
                log.error("读取MCP服务器错误输出失败", e);
            }
        }).start();
    }
    
    @Override
    public ChatResponse sendChatRequest(ChatRequest request) throws IOException {
        // 如果有可用的工具，添加到请求中
        if (!config.getTools().isEmpty() && (request.getTools() == null || request.getTools().isEmpty())) {
            request.setTools(config.getTools());
            log.info("添加 {} 个工具到请求中", config.getTools().size());
        }
        
        if (config.isUseLocalServer() && serverStarted) {
            // 使用本地MCP服务器
            log.info("通过本地MCP服务器调用API");
            return sendRequestToMcpServer(request);
        } else if (config.getServerUrl() != null && !config.getServerUrl().isEmpty()) {
            // 使用远程MCP服务器
            log.info("通过远程MCP服务器调用API");
            return sendRequestToMcpServer(request);
        } else {
            // 直接调用火山引擎
            log.info("直接调用火山引擎DeepSeek服务");
            return volcanoClient.chat(request);
        }
    }
    
    /**
     * 发送请求到MCP服务器
     * 
     * @param request 聊天请求
     * @return 聊天响应
     * @throws IOException 请求失败时抛出
     */
    private ChatResponse sendRequestToMcpServer(ChatRequest request) throws IOException {
        // 检查是否启用MCP协议
        if (config.isUseMcpProtocol()) {
            log.info("使用MCP协议发送请求到服务器");
            return sendRequestViaMcpProtocol(request);
        } else {
            // 使用HTTP REST API方式发送请求（原有逻辑）
            log.info("使用HTTP REST API发送请求到服务器");
            
            // 构建请求URL
            String url = config.getServerUrl();
            if (!url.endsWith("/") && !config.getServerPath().startsWith("/")) {
                url += "/";
            }
            url += config.getServerPath();
            
            // 转换请求为JSON
            String jsonBody = gson.toJson(request);
            
            // 构建请求
            Request httpRequest = new Request.Builder()
                    .url(url)
                    .post(RequestBody.create(jsonBody, JSON))
                    .header("Content-Type", "application/json")
                    .build();
            
            // 发送请求
            try (Response response = httpClient.newCall(httpRequest).execute()) {
                // 检查响应
                if (!response.isSuccessful()) {
                    String error = response.body() != null ? response.body().string() : "Unknown error";
                    log.error("MCP服务器请求失败: {}", error);
                    throw new IOException("MCP服务器请求失败: " + response.code() + " " + error);
                }
                
                // 解析响应
                if (response.body() != null) {
                    String responseBody = response.body().string();
                    log.debug("收到MCP服务器响应: {}", responseBody);
                    ChatResponse chatResponse = gson.fromJson(responseBody, ChatResponse.class);
                    
                    // 处理工具调用
                    chatResponse = handleToolCalls(chatResponse, request);
                    
                    return chatResponse;
                } else {
                    throw new IOException("MCP服务器响应体为空");
                }
            }
        }
    }
    
    /**
     * 使用MCP协议通过TCP连接发送请求到服务器
     * 
     * @param request 聊天请求
     * @return 聊天响应
     * @throws IOException 请求失败时抛出
     */
    private ChatResponse sendRequestViaMcpProtocol(ChatRequest request) throws IOException {
        Socket socket = null;
        try {
            // 解析服务器URL获取主机
            URL url = new URL(config.getServerUrl());
            String host = url.getHost();
            int port = config.getMcpServerPort(); // 使用MCP专用端口
            
            log.info("连接MCP服务器 {}:{}", host, port);
            
            // 创建Socket连接
            socket = new Socket();
            socket.connect(new java.net.InetSocketAddress(host, port), config.getTimeout() * 1000);
            socket.setSoTimeout(config.getTimeout() * 1000);
            
            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
                 BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {
                
                // 创建MCP请求
                JsonObject mcpRequest = createMcpRequest(request);
                String requestJson = gson.toJson(mcpRequest);
                
                log.debug("发送MCP请求: {}", requestJson);
                writer.write(requestJson);
                writer.newLine();
                writer.flush();
                
                // 读取MCP响应
                String responseLine = null;
                long startTime = System.currentTimeMillis();
                long timeoutMillis = config.getTimeout() * 1000L;
                
                while (System.currentTimeMillis() - startTime < timeoutMillis) {
                    if (reader.ready()) {
                        responseLine = reader.readLine();
                        break;
                    }
                    Thread.sleep(100);
                }
                
                if (responseLine == null) {
                    throw new IOException("MCP服务器未返回响应");
                }
                
                log.debug("收到MCP协议响应: {}", responseLine);
                
                // 解析MCP响应
                ChatResponse chatResponse = parseMcpResponse(responseLine);
                
                // 处理工具调用
                if (hasToolCalls(chatResponse)) {
                    chatResponse = handleToolCalls(chatResponse, request);
                }
                
                return chatResponse;
            }
        } catch (Exception e) {
            log.error("MCP协议请求失败: {}", e.getMessage());
            throw new IOException("MCP协议请求失败: " + e.getMessage(), e);
        } finally {
            if (socket != null && !socket.isClosed()) {
                try {
                    socket.close();
                } catch (IOException e) {
                    log.warn("关闭Socket连接失败: {}", e.getMessage());
                }
            }
        }
    }
    
    /**
     * 创建MCP协议请求对象
     * 
     * @param request 聊天请求
     * @return MCP协议请求对象
     */
    private JsonObject createMcpRequest(ChatRequest request) {
        // 创建MCP请求格式
        String requestId = UUID.randomUUID().toString();
        JsonObject mcpRequest = new JsonObject();
        mcpRequest.addProperty("id", requestId);
        
        // 设置模型
        if (request.getModel() != null) {
            mcpRequest.addProperty("model", request.getModel());
        }
        
        // 转换消息
        JsonArray messagesArray = new JsonArray();
        if (request.getMessages() != null) {
            for (Message message : request.getMessages()) {
                JsonObject messageObj = new JsonObject();
                messageObj.addProperty("role", message.getRole());
                messageObj.addProperty("content", message.getContent());
                
                // 如果存在工具ID，添加
                if (message.getToolCallId() != null) {
                    messageObj.addProperty("tool_call_id", message.getToolCallId());
                }
                
                messagesArray.add(messageObj);
            }
        }
        mcpRequest.add("messages", messagesArray);
        
        // 设置温度
        mcpRequest.addProperty("temperature", request.getTemperature());
        
        // 添加最大令牌数
        mcpRequest.addProperty("max_tokens", request.getMaxTokens());
        
        // 设置是否流式响应
        mcpRequest.addProperty("stream", request.isStream());
        
        // 设置工具
        if (request.getTools() != null && !request.getTools().isEmpty()) {
            JsonArray toolsArray = new JsonArray();
            for (Tool tool : request.getTools()) {
                JsonObject toolObj = new JsonObject();
                toolObj.addProperty("name", tool.getName());
                toolObj.addProperty("description", tool.getDescription());
                
                // 添加参数
                if (tool.getParameters() != null) {
                    JsonObject params = gson.toJsonTree(tool.getParameters()).getAsJsonObject();
                    toolObj.add("parameters", params);
                }
                
                toolsArray.add(toolObj);
            }
            mcpRequest.add("tools", toolsArray);
        }
        
        // 添加额外参数
        if (request.getParameters() != null) {
            for (Map.Entry<String, Object> entry : request.getParameters().entrySet()) {
                mcpRequest.add(entry.getKey(), gson.toJsonTree(entry.getValue()));
            }
        }
        
        return mcpRequest;
    }
    
    /**
     * 解析MCP协议响应
     * 
     * @param responseLine MCP响应JSON字符串
     * @return 聊天响应对象
     */
    private ChatResponse parseMcpResponse(String responseLine) {
        try {
            JsonObject responseJson = gson.fromJson(responseLine, JsonObject.class);
            
            ChatResponse chatResponse = new ChatResponse();
            
            // 解析基本字段
            if (responseJson.has("id")) {
                chatResponse.setId(responseJson.get("id").getAsString());
            }
            
            if (responseJson.has("model")) {
                chatResponse.setModel(responseJson.get("model").getAsString());
            }
            
            if (responseJson.has("created")) {
                chatResponse.setCreated(responseJson.get("created").getAsLong());
            }
            
            // 解析选择项
            List<ChatResponse.Choice> choices = new ArrayList<>();
            if (responseJson.has("choices") && responseJson.get("choices").isJsonArray()) {
                JsonArray choicesArray = responseJson.getAsJsonArray("choices");
                
                for (int i = 0; i < choicesArray.size(); i++) {
                    JsonObject choiceObj = choicesArray.get(i).getAsJsonObject();
                    ChatResponse.Choice choice = new ChatResponse.Choice();
                    
                    // 设置索引
                    if (choiceObj.has("index")) {
                        choice.setIndex(choiceObj.get("index").getAsInt());
                    }
                    
                    // 解析消息
                    if (choiceObj.has("message") && choiceObj.get("message").isJsonObject()) {
                        JsonObject messageObj = choiceObj.getAsJsonObject("message");
                        Message message = new Message();
                        
                        if (messageObj.has("role")) {
                            message.setRole(messageObj.get("role").getAsString());
                        }
                        
                        if (messageObj.has("content")) {
                            message.setContent(messageObj.get("content").getAsString());
                        }
                        
                        choice.setMessage(message);
                    }
                    
                    // 解析工具调用
                    if (choiceObj.has("tool_calls") && choiceObj.get("tool_calls").isJsonArray()) {
                        JsonArray toolCallsArray = choiceObj.getAsJsonArray("tool_calls");
                        List<ToolCall> toolCalls = new ArrayList<>();
                        
                        for (int j = 0; j < toolCallsArray.size(); j++) {
                            JsonObject toolCallObj = toolCallsArray.get(j).getAsJsonObject();
                            ToolCall toolCall = new ToolCall();
                            
                            if (toolCallObj.has("id")) {
                                toolCall.setId(toolCallObj.get("id").getAsString());
                            }
                            
                            if (toolCallObj.has("type")) {
                                toolCall.setType(toolCallObj.get("type").getAsString());
                            }
                            
                            if (toolCallObj.has("function")) {
                                toolCall.setFunction(toolCallObj.get("function").getAsString());
                            }
                            
                            if (toolCallObj.has("arguments")) {
                                Map<String, Object> args = gson.fromJson(
                                    toolCallObj.get("arguments"),
                                    new TypeToken<Map<String, Object>>(){}.getType()
                                );
                                toolCall.setArguments(args);
                            }
                            
                            toolCalls.add(toolCall);
                        }
                        
                        choice.setToolCalls(toolCalls);
                    }
                    
                    // 完成原因
                    if (choiceObj.has("finish_reason")) {
                        choice.setFinishReason(choiceObj.get("finish_reason").getAsString());
                    }
                    
                    choices.add(choice);
                }
            }
            chatResponse.setChoices(choices);
            
            // 解析使用量统计
            if (responseJson.has("usage") && responseJson.get("usage").isJsonObject()) {
                JsonObject usageObj = responseJson.getAsJsonObject("usage");
                ChatResponse.Usage usage = new ChatResponse.Usage();
                
                if (usageObj.has("prompt_tokens")) {
                    usage.setPromptTokens(usageObj.get("prompt_tokens").getAsInt());
                }
                
                if (usageObj.has("completion_tokens")) {
                    usage.setCompletionTokens(usageObj.get("completion_tokens").getAsInt());
                }
                
                if (usageObj.has("total_tokens")) {
                    usage.setTotalTokens(usageObj.get("total_tokens").getAsInt());
                }
                
                chatResponse.setUsage(usage);
            }
            
            return chatResponse;
        } catch (Exception e) {
            log.error("解析MCP响应失败: {}", e.getMessage());
            throw new RuntimeException("解析MCP响应失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 检查响应中是否包含工具调用
     * 
     * @param response 聊天响应
     * @return 是否包含工具调用
     */
    private boolean hasToolCalls(ChatResponse response) {
        if (response.getChoices() == null || response.getChoices().isEmpty()) {
            return false;
        }
        
        ChatResponse.Choice choice = response.getChoices().get(0);
        return choice.getToolCalls() != null && !choice.getToolCalls().isEmpty();
    }
    
    /**
     * 处理响应中的工具调用
     * 
     * @param response 聊天响应
     * @param originalRequest 原始请求
     * @return 处理后的响应
     * @throws IOException 处理失败时抛出
     */
    private ChatResponse handleToolCalls(ChatResponse response, ChatRequest originalRequest) throws IOException {
        if (response.getChoices() == null || response.getChoices().isEmpty()) {
            return response;
        }
        
        ChatResponse.Choice choice = response.getChoices().get(0);
        if (choice.getMessage() == null || choice.getToolCalls() == null || choice.getToolCalls().isEmpty()) {
            return response;
        }
        
        // 工具调用存在
        List<ToolCall> toolCalls = choice.getToolCalls();
        log.info("发现 {} 个工具调用", toolCalls.size());
        
        // 处理每个工具调用
        List<Message> newMessages = new ArrayList<>(originalRequest.getMessages());
        newMessages.add(choice.getMessage());  // 添加助手消息，包含工具调用
        
        for (ToolCall toolCall : toolCalls) {
            log.info("处理工具调用: {} - {}", toolCall.getFunction(), gson.toJson(toolCall.getArguments()));
            
            // 这里应该实际执行工具调用，获取结果
            String result = executeToolCall(toolCall);
            
            // 创建工具响应消息
            Message toolResultMessage = new Message("tool", result, toolCall.getId());
            newMessages.add(toolResultMessage);
            
            log.info("工具调用结果: {}", result);
        }
        
        // 创建新的请求，包含所有历史消息和工具结果
        ChatRequest newRequest = new ChatRequest();
        newRequest.setModel(originalRequest.getModel());
        newRequest.setMessages(newMessages);
        newRequest.setTemperature(originalRequest.getTemperature());
        newRequest.setMaxTokens(originalRequest.getMaxTokens());
        newRequest.setStream(originalRequest.isStream());
        newRequest.setTools(originalRequest.getTools());
        
        // 发送新请求，获取最终响应
        return sendRequestToMcpServer(newRequest);
    }
    
    /**
     * 执行工具调用
     * 
     * @param toolCall 工具调用
     * @return 工具调用结果
     */
    private String executeToolCall(ToolCall toolCall) {
        String functionName = toolCall.getFunction();
        Map<String, Object> args = toolCall.getArguments();
        
        log.info("执行工具: {} 参数: {}", functionName, gson.toJson(args));
        
        // 检查是否启用MCP协议
        if (config.isUseMcpProtocol()) {
            log.info("使用MCP协议执行工具调用");
            return executeToolViaRemoteMcp(toolCall);
        } else {
            // 本地模拟执行工具（仅用于示例）
            log.warn("使用本地模拟执行工具，这不会调用实际服务器功能！");
            
            // 示例：根据工具名执行不同操作
            switch (functionName) {
                case "get_weather":
                    return "晴天，气温25摄氏度";
                case "search":
                    return "搜索结果: " + args.getOrDefault("query", "");
                case "calculator":
                    return "计算结果: 42";
                default:
                    return "工具 " + functionName + " 执行成功，参数: " + gson.toJson(args);
            }
        }
    }
    
    /**
     * 通过MCP协议调用远程服务器执行工具
     * 
     * @param toolCall 工具调用
     * @return 工具调用结果
     */
    private String executeToolViaRemoteMcp(ToolCall toolCall) {
        Socket socket = null;
        try {
            // 解析服务器URL获取主机
            URL url = new URL(config.getServerUrl());
            String host = url.getHost();
            int port = config.getMcpServerPort();
            
            log.info("连接MCP服务器执行工具: {}:{}", host, port);
            
            // 创建Socket连接
            socket = new Socket();
            socket.connect(new java.net.InetSocketAddress(host, port), config.getTimeout() * 1000);
            socket.setSoTimeout(config.getTimeout() * 1000);
            
            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
                 BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {
                
                // 创建MCP工具调用请求
                JsonObject mcpToolRequest = createMcpToolRequest(toolCall);
                String requestJson = gson.toJson(mcpToolRequest);
                
                log.debug("发送MCP工具调用请求: {}", requestJson);
                writer.write(requestJson);
                writer.newLine();
                writer.flush();
                
                // 读取MCP响应
                String responseLine = null;
                long startTime = System.currentTimeMillis();
                long timeoutMillis = config.getTimeout() * 1000L;
                
                while (System.currentTimeMillis() - startTime < timeoutMillis) {
                    if (reader.ready()) {
                        responseLine = reader.readLine();
                        break;
                    }
                    Thread.sleep(100);
                }
                
                if (responseLine == null) {
                    log.error("MCP工具执行未返回响应");
                    return "MCP工具执行超时";
                }
                
                log.debug("收到MCP工具调用响应: {}", responseLine);
                
                // 解析响应获取工具结果
                return parseMcpToolResponse(responseLine);
            }
        } catch (Exception e) {
            log.error("MCP工具调用失败: {}", e.getMessage());
            return "MCP工具调用失败: " + e.getMessage();
        } finally {
            if (socket != null && !socket.isClosed()) {
                try {
                    socket.close();
                } catch (IOException e) {
                    log.warn("关闭Socket连接失败: {}", e.getMessage());
                }
            }
        }
    }
    
    /**
     * 创建MCP工具调用请求
     * 
     * @param toolCall 工具调用
     * @return MCP请求对象
     */
    private JsonObject createMcpToolRequest(ToolCall toolCall) {
        // 创建MCP请求格式
        String requestId = UUID.randomUUID().toString();
        JsonObject mcpRequest = new JsonObject();
        mcpRequest.addProperty("id", requestId);
        
        // 添加工具调用信息
        JsonArray toolCallsArray = new JsonArray();
        JsonObject toolCallObj = new JsonObject();
        
        toolCallObj.addProperty("id", toolCall.getId());
        toolCallObj.addProperty("type", "function");
        toolCallObj.addProperty("function", toolCall.getFunction());
        
        // 添加参数
        String argumentsJson = gson.toJson(toolCall.getArguments());
        toolCallObj.addProperty("arguments", argumentsJson);
        
        toolCallsArray.add(toolCallObj);
        mcpRequest.add("tool_calls", toolCallsArray);
        
        return mcpRequest;
    }
    
    /**
     * 解析MCP工具调用响应
     * 
     * @param responseLine MCP响应JSON字符串
     * @return 工具调用结果
     */
    private String parseMcpToolResponse(String responseLine) {
        try {
            JsonObject responseJson = gson.fromJson(responseLine, JsonObject.class);
            
            // 检查是否包含结果字段
            if (responseJson.has("result")) {
                return responseJson.get("result").getAsString();
            } else if (responseJson.has("response")) {
                return responseJson.get("response").getAsString();
            } else if (responseJson.has("content")) {
                return responseJson.get("content").getAsString();
            }
            
            // 如果没有明确的结果字段，返回整个响应
            return responseLine;
        } catch (Exception e) {
            log.error("解析MCP工具响应失败: {}", e.getMessage());
            return "解析MCP工具响应失败: " + e.getMessage();
        }
    }
    
    /**
     * 从MCP服务器获取可用工具列表
     */
    private void fetchToolsFromMcpServer() {
        try {
            log.info("尝试从MCP服务器获取可用工具列表...");
            
            // 构建请求URL
            String baseUrl = config.getServerUrl();
            if (!baseUrl.endsWith("/")) {
                baseUrl += "/";
            }
            
            // MCP服务器通常在/tools端点提供工具列表
            String toolsEndpoint = baseUrl + "tools";
            log.info("请求工具列表端点: {}", toolsEndpoint);
            
            // 构建请求
            Request httpRequest = new Request.Builder()
                    .url(toolsEndpoint)
                    .get()
                    .build();
            
            // 发送请求
            try (Response response = httpClient.newCall(httpRequest).execute()) {
                // 检查响应
                if (!response.isSuccessful()) {
                    log.warn("获取MCP服务器工具列表失败: {} {}", response.code(), response.message());
                    // 尝试备用端点
                    fetchToolsFromAlternativeEndpoint(baseUrl);
                    return;
                }
                
                // 解析响应
                if (response.body() != null) {
                    String responseBody = response.body().string();
                    log.debug("收到MCP服务器工具列表响应: {}", responseBody);
                    
                    try {
                        // 解析工具列表
                        Type toolListType = new TypeToken<List<Tool>>(){}.getType();
                        List<Tool> serverTools = gson.fromJson(responseBody, toolListType);
                        
                        if (serverTools != null && !serverTools.isEmpty()) {
                            log.info("从MCP服务器获取到 {} 个可用工具", serverTools.size());
                            
                            // 清除现有工具并添加服务器提供的工具
                            config.clearTools();
                            config.addTools(serverTools);
                            
                            // 输出获取到的工具信息
                            for (Tool tool : serverTools) {
                                log.info("服务器工具: {} - {}", tool.getName(), tool.getDescription());
                            }
                        } else {
                            log.info("MCP服务器未提供可用工具，尝试备用端点");
                            fetchToolsFromAlternativeEndpoint(baseUrl);
                        }
                    } catch (Exception e) {
                        log.error("解析MCP服务器工具列表失败", e);
                        fetchToolsFromAlternativeEndpoint(baseUrl);
                    }
                }
            }
        } catch (Exception e) {
            log.error("获取MCP服务器工具列表失败", e);
        }
    }
    
    /**
     * 尝试从备用端点获取工具列表
     * 
     * @param baseUrl 服务器基础URL
     */
    private void fetchToolsFromAlternativeEndpoint(String baseUrl) {
        try {
            log.info("尝试从备用端点获取工具列表...");
            
            // 一些MCP服务器可能使用不同的端点提供工具信息
            String[] alternativeEndpoints = {
                baseUrl + "functions",
                baseUrl + "api/tools",
                baseUrl + "api/functions"
            };
            
            for (String endpoint : alternativeEndpoints) {
                log.info("尝试备用端点: {}", endpoint);
                
                Request request = new Request.Builder()
                        .url(endpoint)
                        .get()
                        .build();
                
                try (Response response = httpClient.newCall(request).execute()) {
                    if (response.isSuccessful() && response.body() != null) {
                        String responseBody = response.body().string();
                        
                        try {
                            // 尝试解析不同格式的工具列表
                            List<Tool> tools = parseToolsFromResponse(responseBody);
                            
                            if (tools != null && !tools.isEmpty()) {
                                log.info("从备用端点获取到 {} 个工具", tools.size());
                                config.clearTools();
                                config.addTools(tools);
                                return;
                            }
                        } catch (Exception e) {
                            log.debug("从备用端点解析工具失败: {}", e.getMessage());
                        }
                    }
                }
            }
            
            log.warn("无法从任何端点获取工具列表");
        } catch (Exception e) {
            log.error("从备用端点获取工具列表失败", e);
        }
    }
    
    /**
     * 尝试解析不同格式的工具列表响应
     * 
     * @param responseBody 响应体
     * @return 工具列表
     */
    private List<Tool> parseToolsFromResponse(String responseBody) {
        try {
            // 首先尝试直接解析为工具列表
            Type toolListType = new TypeToken<List<Tool>>(){}.getType();
            List<Tool> tools = gson.fromJson(responseBody, toolListType);
            
            if (tools != null && !tools.isEmpty()) {
                return tools;
            }
            
            // 尝试解析为包含工具列表的对象
            JsonObject jsonObject = gson.fromJson(responseBody, JsonObject.class);
            
            // 检查常见的字段名
            String[] possibleFieldNames = {"tools", "functions", "available_tools", "available_functions"};
            
            for (String fieldName : possibleFieldNames) {
                if (jsonObject.has(fieldName) && jsonObject.get(fieldName).isJsonArray()) {
                    tools = gson.fromJson(jsonObject.get(fieldName), toolListType);
                    if (tools != null && !tools.isEmpty()) {
                        return tools;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("解析工具列表失败: {}", e.getMessage());
        }
        
        return new ArrayList<>();
    }

    /**
     * 判断是否为JAR形式的服务器
     * 
     * @return 是否为JAR服务器
     */
    private boolean isJarServer() {
        // 检查命令本身是否以.jar结尾
        if (config.getServerCommand() != null && config.getServerCommand().toLowerCase().endsWith(".jar")) {
            log.info("检测到JAR服务器: 命令直接引用JAR文件 - {}", config.getServerCommand());
            return true;
        }
        
        // 检查命令参数是否包含.jar
        String[] args = config.getServerArgs();
        if (args != null && args.length > 0) {
            for (String arg : args) {
                if (arg != null && arg.toLowerCase().endsWith(".jar")) {
                    log.info("检测到JAR服务器: 参数中包含JAR文件 - {}", arg);
                    return true;
                }
            }
        }
        
        // 检查命令是否为java且参数中包含-jar
        if (config.getServerCommand() != null && "java".equals(config.getServerCommand()) && args != null && args.length > 1) {
            for (int i = 0; i < args.length - 1; i++) {
                if ("-jar".equals(args[i])) {
                    log.info("检测到JAR服务器: java -jar 命令");
                    return true;
                }
            }
        }
        
        log.info("未检测到JAR形式服务器，命令: {}, 参数: {}", 
                config.getServerCommand(), 
                args != null ? Arrays.toString(args) : "无");
        return false;
    }

    /**
     * 获取JAR文件路径
     * 
     * @return JAR文件路径
     */
    private String getJarPath() {
        // 如果命令本身是JAR文件
        if (config.getServerCommand() != null && config.getServerCommand().toLowerCase().endsWith(".jar")) {
            log.info("找到JAR路径(命令): {}", config.getServerCommand());
            return config.getServerCommand();
        }
        
        // 检查参数中是否有JAR文件
        String[] args = config.getServerArgs();
        if (args != null && args.length > 0) {
            for (String arg : args) {
                if (arg != null && arg.toLowerCase().endsWith(".jar")) {
                    log.info("找到JAR路径(参数): {}", arg);
                    return arg;
                }
            }
            
            // 检查是否有-jar参数后跟着JAR路径
            if (config.getServerCommand() != null && "java".equals(config.getServerCommand())) {
                for (int i = 0; i < args.length - 1; i++) {
                    if ("-jar".equals(args[i]) && args[i + 1] != null) {
                        log.info("找到JAR路径(java -jar参数): {}", args[i + 1]);
                        return args[i + 1];
                    }
                }
            }
        }
        
        log.warn("未找到有效的JAR文件路径");
        return null;
    }

    /**
     * 从已启动的本地JAR服务器获取工具列表
     */
    private void fetchToolsFromRunningServer() {
        try {
            log.info("尝试从已启动的本地JAR服务器获取工具列表...");
            
            // 检查服务器URL，应该已经设置了默认值
            String baseUrl = config.getServerUrl();
            if (baseUrl == null || baseUrl.isEmpty()) {
                log.warn("未找到服务器URL，无法获取工具列表");
                return;
            }
            
            // 检查服务器类型，如果是jar类型，尝试通过反射获取工具
            if ("jar".equals(config.getServerType())) {
                if (fetchToolsFromJarFile()) {
                    log.info("已通过反射从JAR文件获取工具列表");
                    return;
                }
            }
        } catch (Exception e) {
            log.error("从已启动的JAR服务器获取工具列表失败: {}", e.getMessage());
        }
    }

    /**
     * 通过反射从JAR文件中获取工具列表
     * @return 是否成功获取工具列表
     */
    private boolean fetchToolsFromJarFile() {
        try {
            log.info("尝试通过反射从JAR文件中获取工具列表...");
            
            String jarPath = null;
            
            // 确定JAR文件路径
            if (config.getServerCommand() != null) {
                if (config.getServerCommand().endsWith(".jar")) {
                    // 如果命令直接是JAR文件
                    jarPath = config.getServerCommand();
                } else if (config.getServerArgs() != null) {
                    // 如果命令是java -jar xxx.jar，则从参数中查找JAR文件
                    for (String arg : config.getServerArgs()) {
                        if (arg.endsWith(".jar")) {
                            jarPath = arg;
                            break;
                        }
                    }
                }
            }
            
            if (jarPath == null) {
                log.warn("未找到JAR文件路径，无法通过反射获取工具列表");
                return false;
            }
            
            // 使用JAR工具扫描器扫描JAR文件中的工具
            List<Tool> tools = JarToolScanner.scanTools(jarPath);
            
            if (tools.isEmpty()) {
                log.warn("未从JAR文件中扫描到工具列表");
                return false;
            }
            
            // 更新工具列表
            log.info("从JAR文件中扫描到 {} 个工具", tools.size());
            config.clearTools();
            config.addTools(tools);
            return true;
            
        } catch (Exception e) {
            log.error("通过反射获取工具列表失败: {}", e.getMessage());
            return false;
        }
    }
} 