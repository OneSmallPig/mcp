package com.example.mcp.client;

import com.example.mcp.client.config.McpClientConfig;
import com.example.mcp.client.model.ChatRequest;
import com.example.mcp.client.model.ChatResponse;
import com.example.mcp.client.model.Message;
import com.example.mcp.client.model.Tool;
import com.example.mcp.client.model.ToolCall;
import com.example.mcp.client.service.McpService;
import com.example.mcp.client.service.McpSseService;
import com.example.mcp.client.service.SimpleSseEventListener;
import com.example.mcp.client.service.BufferedSseEventListener;
import com.example.mcp.client.service.impl.McpServiceImpl;
import com.example.mcp.client.service.impl.McpSseServiceImpl;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

/**
 * MCP客户端应用程序入口
 */
public class McpClientApplication {
    private static final Logger log = LoggerFactory.getLogger(McpClientApplication.class);
    private static final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    
    public static void main(String[] args) {
        log.info("启动MCP客户端应用程序");
        
        // 创建配置
        McpClientConfig config = McpClientConfig.getInstance();
        
        // 检查本地MCP服务器配置
        if (config.isUseLocalServer()) {
            // 如果配置了本地服务器但没有指定URL，设置默认URL
            config.setDefaultLocalServerUrl();
            log.info("准备启动本地MCP服务: {}", config.getServerCommand());
            //确保本地服务器已启动
            ensureLocalServerStarted();
        }
        
        // 输出可用工具信息
        List<Tool> tools = config.getTools();
        if (!tools.isEmpty()) {
            System.out.println("已加载 " + tools.size() + " 个MCP工具:");
            System.out.println("（这些工具可能来自配置文件或从MCP服务器自动获取）");
            for (Tool tool : tools) {
                String paramInfo = "";
                if (tool.getParameters() != null && !tool.getParameters().isEmpty()) {
                    paramInfo = " [参数: " + tool.getParameters().size() + "个]";
                }
                System.out.println("  - " + tool.getName() + paramInfo + ": " + tool.getDescription());
            }
        } else {
            System.out.println("未加载任何MCP工具。如果您配置了MCP服务器但未显示工具，请检查服务器是否正确提供工具列表API。");
        }
        
        if (args.length > 0) {
            if (args[0].equals("--interactive")) {
                // 交互模式 - 使用标准MCP服务，会自动启动本地服务器
                runInteractiveMode(new McpServiceImpl());
            } else if (args[0].equals("--sse")) {
                // SSE交互模式 - 需要先确保本地服务器已启动
                runSseInteractiveMode(new McpSseServiceImpl());
            } else if (args[0].equals("--simple")) {
                // 简化SSE模式 - 也需要确保本地服务器已启动
                runSimpleMode();
            }
        } else {
            // 演示模式 - 使用标准MCP服务，会自动启动本地服务器
            runDemoMode(new McpServiceImpl());
        }
    }
    
    /**
     * 确保本地MCP服务器已启动
     */
    private static void ensureLocalServerStarted() {
        McpClientConfig config = McpClientConfig.getInstance();
        if (config.isUseLocalServer() && config.getServerCommand() != null) {
            try {
                log.info("正在启动本地MCP服务器...");

                // 创建临时的McpService实例来启动本地服务器
                McpService tempService = new McpServiceImpl();
                
                // 发送一个简单请求以触发服务器启动
                ChatRequest request = new ChatRequest();
                List<Message> messages = new ArrayList<>();
                messages.add(new Message("system", "test"));
                request.setMessages(messages);
                
                try {
                    tempService.sendChatRequest(request);
                    log.info("本地MCP服务器已成功启动");
                } catch (Exception e) {
                    log.warn("测试请求失败，但本地服务器可能已启动: {}", e.getMessage());
                }
                
                // 等待服务器完全启动
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                
            } catch (Exception e) {
                log.error("启动本地MCP服务器失败: {}", e.getMessage());
                System.err.println("警告: 启动本地MCP服务器失败，SSE模式可能无法正常工作");
            }
        }
    }
    
    /**
     * 运行交互模式
     * 
     * @param mcpService MCP服务
     */
    private static void runInteractiveMode(McpService mcpService) {
        Scanner scanner = new Scanner(System.in);
        List<Message> messages = new ArrayList<>();
        
        System.out.println("欢迎使用MCP客户端！输入'exit'退出。");
        
        // 添加系统提示
        messages.add(new Message("system", "You are a helpful assistant. When user requests a tool function, always try to call the available tools to satisfy their request."));
        
        while (true) {
            System.out.print("\nUser: ");
            String input = scanner.nextLine().trim();
            
            if ("exit".equalsIgnoreCase(input)) {
                break;
            }
            
            // 添加用户消息
            messages.add(new Message("user", input));
            
            try {
                // 创建请求
                ChatRequest request = new ChatRequest();
                request.setMessages(new ArrayList<>(messages));
                
                // 发送请求
                ChatResponse response = mcpService.sendChatRequest(request);
                
                // 处理响应
                if (response.getChoices() != null && !response.getChoices().isEmpty()) {
                    ChatResponse.Choice choice = response.getChoices().get(0);
                    Message assistantMessage = choice.getMessage();
                    String content = assistantMessage.getContent();
                    
                    System.out.println("\nAssistant: " + (content != null ? content : "(使用工具中...)"));
                    
                    // 显示工具调用
                    if (choice.getToolCalls() != null && !choice.getToolCalls().isEmpty()) {
                        System.out.println("\n[工具调用]:");
                        for (ToolCall toolCall : choice.getToolCalls()) {
                            System.out.println("  - 工具: " + toolCall.getFunction());
                            System.out.println("    参数: " + gson.toJson(toolCall.getArguments()));
                            if (toolCall.getResult() != null) {
                                System.out.println("    结果: " + toolCall.getResult());
                            }
                        }
                    }
                    
                    // 添加助手消息到历史
                    messages.add(assistantMessage);
                    
                    // 显示Token使用情况
                    if (response.getUsage() != null) {
                        System.out.println("\n[Token使用: 提示词 " + response.getUsage().getPromptTokens() + 
                                ", 完成 " + response.getUsage().getCompletionTokens() + 
                                ", 总计 " + response.getUsage().getTotalTokens() + "]");
                    }
                }
            } catch (IOException e) {
                System.err.println("发送请求失败: " + e.getMessage());
                log.error("发送请求失败", e);
            }
        }
        
        System.out.println("感谢使用MCP客户端，再见！");
        scanner.close();
    }
    
    /**
     * 运行SSE交互模式
     * 
     * @param mcpSseService MCP SSE服务
     */
    private static void runSseInteractiveMode(McpSseService mcpSseService) {
        Scanner scanner = new Scanner(System.in);
        List<Message> messages = new ArrayList<>();
        
        System.out.println("欢迎使用MCP客户端SSE模式！输入'exit'退出。");
        
        // 添加系统提示
        messages.add(new Message("system", "You are a helpful assistant. When user requests a tool function, always try to call the available tools to satisfy their request."));
        
        while (true) {
            System.out.print("\nUser: ");
            String input = scanner.nextLine().trim();
            
            if ("exit".equalsIgnoreCase(input)) {
                break;
            }
            
            // 添加用户消息
            messages.add(new Message("user", input));
            
            try {
                // 创建缓冲式应答收集器
                BufferedSseEventListener bufferedListener = new BufferedSseEventListener() {
                    private final List<ToolCall> currentToolCalls = new ArrayList<>();
                    
                    @Override
                    public void onToolCall(ToolCall toolCall) {
                        System.out.println("\n[工具调用] " + toolCall.getFunction());
                        System.out.println("参数: " + gson.toJson(toolCall.getArguments()));
                        currentToolCalls.add(toolCall);
                    }
                    
                    @Override
                    public void onToolResult(String function, String result) {
                        System.out.println("[工具结果] " + function + ": " + result);
                    }
                    
                    @Override
                    public void onComplete() {
                        // 调用父类方法
                        super.onComplete();
                        
                        // 获取并输出完整响应
                        String completeResponse = getCompleteResponse();
                        if (!completeResponse.isEmpty()) {
                            System.out.println("\nAssistant: " + completeResponse);
                            
                            // 将完整回复添加到历史
                            Message assistantMessage = new Message("assistant", completeResponse);
                            assistantMessage.setToolCalls(currentToolCalls);
                            messages.add(assistantMessage);
                        }
                    }
                    
                    @Override
                    public void onError(Throwable t) {
                        System.err.println("\n错误: " + t.getMessage());
                    }
                };
                
                // 发送请求
                mcpSseService.sendPrompt(messages, input, bufferedListener);
                
            } catch (IOException e) {
                System.err.println("发送请求失败: " + e.getMessage());
                log.error("发送请求失败", e);
            }
        }
        
        mcpSseService.close();
        System.out.println("感谢使用MCP客户端，再见！");
        scanner.close();
    }
    
    /**
     * 运行简化模式
     * 使用更简单的API演示MCP工具调用
     */
    private static void runSimpleMode() {
        System.out.println("启动简化MCP客户端模式...");
        
        Scanner scanner = new Scanner(System.in);
        SimpleMcpClient client = new SimpleMcpClient();
        
        System.out.println("欢迎使用MCP简化客户端！输入'exit'退出。");
        
        while (true) {
            System.out.print("\nUser: ");
            String input = scanner.nextLine().trim();
            
            if ("exit".equalsIgnoreCase(input)) {
                break;
            }
            
            try {
                client.chat(input, new SimpleMcpClient.ChatCallback() {
                    @Override
                    public void onAiResponse(String text) {
                        System.out.print(text);
                    }
                    
                    @Override
                    public void onToolUse(String toolName, String parameters) {
                        System.out.println("\n\n[使用工具] " + toolName);
                        System.out.println("参数: " + parameters);
                    }
                    
                    @Override
                    public void onToolResult(String toolName, String result) {
                        System.out.println("[工具结果] " + toolName + ": " + result);
                    }
                    
                    @Override
                    public void onFinished() {
                        System.out.println("\n");
                    }
                    
                    @Override
                    public void onError(String errorMessage) {
                        System.err.println("\n错误: " + errorMessage);
                    }
                });
            } catch (Exception e) {
                System.err.println("请求失败: " + e.getMessage());
            }
        }
        
        client.close();
        System.out.println("感谢使用MCP简化客户端，再见！");
        scanner.close();
    }
    
    /**
     * 运行演示模式
     * 
     * @param mcpService MCP服务
     */
    private static void runDemoMode(McpService mcpService) {
        try {
            log.info("运行演示模式");
            
            // 创建聊天历史
            List<Message> messages = new ArrayList<>();
            messages.add(new Message("system", "You are a helpful assistant. When user requests a tool function, always try to call the available tools to satisfy their request."));
            
            // 如果有工具，演示工具调用
            if (!McpClientConfig.getInstance().getTools().isEmpty()) {
                messages.add(new Message("user", "请帮我查询一下北京的天气。"));
            } else {
                messages.add(new Message("user", "你好，请介绍一下自己。"));
            }
            
            // 创建请求
            ChatRequest request = new ChatRequest();
            request.setMessages(messages);
            
            log.info("发送示例请求...");
            
            // 发送请求
            ChatResponse response = mcpService.sendChatRequest(request);
            
            // 处理响应
            if (response.getChoices() != null && !response.getChoices().isEmpty()) {
                ChatResponse.Choice choice = response.getChoices().get(0);
                Message assistantMessage = choice.getMessage();
                
                log.info("收到响应: {}", assistantMessage.getContent());
                System.out.println("\nAssistant: " + assistantMessage.getContent());
                
                // 显示工具调用
                if (choice.getToolCalls() != null && !choice.getToolCalls().isEmpty()) {
                    log.info("工具调用: {}", gson.toJson(choice.getToolCalls()));
                    System.out.println("\n[工具调用]:");
                    for (ToolCall toolCall : choice.getToolCalls()) {
                        System.out.println("  - 工具: " + toolCall.getFunction());
                        System.out.println("    参数: " + gson.toJson(toolCall.getArguments()));
                        if (toolCall.getResult() != null) {
                            System.out.println("    结果: " + toolCall.getResult());
                        }
                    }
                }
                
                // 显示Token使用情况
                if (response.getUsage() != null) {
                    log.info("Token使用: 提示词 {}, 完成 {}, 总计 {}", 
                            response.getUsage().getPromptTokens(),
                            response.getUsage().getCompletionTokens(),
                            response.getUsage().getTotalTokens());
                }
            }
            
            log.info("演示完成");
        } catch (IOException e) {
            log.error("演示运行失败", e);
        }
    }
} 