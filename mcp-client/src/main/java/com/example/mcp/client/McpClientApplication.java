package com.example.mcp.client;

import com.example.mcp.client.config.McpClientConfig;
import com.example.mcp.client.model.ChatRequest;
import com.example.mcp.client.model.ChatResponse;
import com.example.mcp.client.model.Message;
import com.example.mcp.client.model.Tool;
import com.example.mcp.client.model.ToolCall;
import com.example.mcp.client.service.McpService;
import com.example.mcp.client.service.McpServiceImpl;
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
        
        // 创建MCP服务
        McpService mcpService = new McpServiceImpl();
        
        // 输出可用工具信息
        List<Tool> tools = McpClientConfig.getInstance().getTools();
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
            System.out.println();
        } else {
            System.out.println("未加载任何MCP工具。如果您配置了MCP服务器但未显示工具，请检查服务器是否正确提供工具列表API。");
            System.out.println();
        }
        
        if (args.length > 0 && args[0].equals("--interactive")) {
            // 交互模式
            runInteractiveMode(mcpService);
        } else {
            // 演示模式
            runDemoMode(mcpService);
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