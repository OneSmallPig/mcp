package com.example.mcp.client;

import com.example.mcp.client.config.McpClientConfig;
import com.example.mcp.client.model.Message;
import com.example.mcp.client.service.McpSseService;
import com.example.mcp.client.service.SimpleSseEventListener;
import com.example.mcp.client.service.BufferedSseEventListener;
import com.example.mcp.client.service.impl.McpSseServiceImpl;
import com.example.mcp.client.model.ToolCall;
import com.google.gson.Gson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.Scanner;

/**
 * 简化的MCP客户端，专为初学者设计
 * 提供简单易用的API，隐藏复杂的实现细节
 * 支持Cursor风格的MCP SSE通信
 */
public class SimpleMcpClient {
    private static final Logger log = LoggerFactory.getLogger(SimpleMcpClient.class);
    private final McpSseService sseService;
    private final Gson gson;
    private final List<String> messageHistory;
    private String currentResponse;
    
    /**
     * 创建简化客户端
     */
    public SimpleMcpClient() {
        this.sseService = new McpSseServiceImpl();
        this.gson = new Gson();
        this.messageHistory = new ArrayList<>();
        this.currentResponse = "";
    }
    
    /**
     * 发送消息并获取AI回复
     * 
     * @param userMessage 用户消息
     * @param callback 回调接口，用于接收响应
     */
    public void chat(String userMessage, ChatCallback callback) {
        try {
            log.info("发送用户消息: {}", userMessage);
            
            // 保存用户消息到历史
            messageHistory.add(userMessage);
            currentResponse = "";
            
            // 创建缓冲式监听器
            BufferedSseEventListener listener = new BufferedSseEventListener() {
                @Override
                public void onToolCall(ToolCall toolCall) {
                    callback.onToolUse(toolCall.getFunction(), 
                                     gson.toJson(toolCall.getArguments()));
                }
                
                @Override
                public void onToolResult(String function, String result) {
                    callback.onToolResult(function, result);
                }
                
                @Override
                public void onComplete() {
                    // 调用父类方法
                    super.onComplete();
                    
                    // 获取完整响应
                    currentResponse = getCompleteResponse();
                    
                    // 只在这里输出一次完整响应
                    if (!currentResponse.isEmpty()) {
                        callback.onAiResponse(currentResponse);
                        // 保存AI回复到历史
                        messageHistory.add(currentResponse);
                    }
                    
                    callback.onFinished();
                }
                
                @Override
                public void onError(Throwable t) {
                    log.error("聊天错误", t);
                    callback.onError(t.getMessage());
                }
            };
            
            // 发送请求
            sseService.sendPrompt(userMessage, listener);
            
        } catch (IOException e) {
            log.error("发送请求失败", e);
            callback.onError("发送请求失败: " + e.getMessage());
        }
    }
    
    /**
     * 异步发送消息，返回CompletableFuture
     * 适合有编程基础的用户
     * 
     * @param userMessage 用户消息
     * @return 包含AI回复的CompletableFuture
     */
    public CompletableFuture<String> chatAsync(String userMessage) {
        CompletableFuture<String> future = new CompletableFuture<>();
        
        try {
            // 创建缓冲式监听器
            BufferedSseEventListener listener = new BufferedSseEventListener() {
                @Override
                public void onComplete() {
                    // 完成时返回完整响应
                    future.complete(getCompleteResponse());
                }
                
                @Override
                public void onError(Throwable t) {
                    future.completeExceptionally(t);
                }
            };
            
            // 发送请求
            sseService.sendPrompt(userMessage, listener);
            
        } catch (IOException e) {
            future.completeExceptionally(e);
        }
        
        return future;
    }
    
    /**
     * 获取消息历史
     * 
     * @return 消息历史列表
     */
    public List<String> getMessageHistory() {
        return new ArrayList<>(messageHistory);
    }
    
    /**
     * 清除消息历史
     */
    public void clearHistory() {
        messageHistory.clear();
        currentResponse = "";
    }
    
    /**
     * 关闭客户端
     */
    public void close() {
        sseService.close();
    }
    
    /**
     * 聊天回调接口
     */
    public interface ChatCallback {
        /**
         * 接收AI实时回复
         * 
         * @param text 文本片段
         */
        void onAiResponse(String text);
        
        /**
         * AI使用工具时调用
         * 
         * @param toolName 工具名称
         * @param parameters 参数JSON
         */
        void onToolUse(String toolName, String parameters);
        
        /**
         * 工具返回结果时调用
         * 
         * @param toolName 工具名称
         * @param result 结果
         */
        void onToolResult(String toolName, String result);
        
        /**
         * 对话完成时调用
         */
        void onFinished();
        
        /**
         * 发生错误时调用
         * 
         * @param errorMessage 错误信息
         */
        void onError(String errorMessage);
    }
    
    /**
     * 示例方法：使用SSE连接与大模型对话
     * 
     * @param prompt 提示词
     */
    public void chatWithSse(String prompt) {
        try {
            log.info("开始使用SSE与大模型对话...");
            
            // 创建SSE服务
            McpSseService sseService = new McpSseServiceImpl();
            
            // 创建缓冲式SSE事件监听器
            BufferedSseEventListener listener = new BufferedSseEventListener() {
                @Override
                public void onToolCall(ToolCall toolCall) {
                    log.info("收到工具调用: {} {}", toolCall.getFunction(), toolCall.getArguments());
                    System.out.println("\n[调用工具: " + toolCall.getFunction() + "]");
                }
                
                @Override
                public void onToolResult(String function, String result) {
                    log.info("工具结果: {} = {}", function, result);
                    System.out.println("[工具结果: " + result + "]");
                }
                
                @Override
                public void onComplete() {
                    // 调用父类方法
                    super.onComplete();
                    
                    // 获取并输出完整响应
                    String completeResponse = getCompleteResponse();
                    if (!completeResponse.isEmpty()) {
                        System.out.println("\nAssistant: " + completeResponse);
                    }
                    
                    log.info("对话完成");
                    System.out.println("\n对话完成！");
                }
                
                @Override
                public void onError(Throwable t) {
                    log.error("对话错误", t);
                    System.err.println("发生错误: " + t.getMessage());
                }
            };
            
            // 发送提示词，开始对话
            sseService.sendPrompt(prompt, listener);
            
            // 等待对话完成
            // 注意：在实际应用中，应使用更优雅的方式等待对话完成，如CountDownLatch
            try {
                Thread.sleep(30000); // 简单起见，等待30秒
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            
            // 关闭连接
            sseService.close();
            
        } catch (IOException e) {
            log.error("SSE对话失败", e);
        }
    }
    
    /**
     * 主要用于示例和测试
     */
    public static void main(String[] args) {
        SimpleMcpClient client = new SimpleMcpClient();
        final String END_MARKER = "/end";
        final String CANCEL_MARKER = "/cancel";
        
        System.out.println("MCP客户端简化版 - 支持多行输入");
        System.out.println("- 输入'" + END_MARKER + "'结束多行输入并发送消息");
        System.out.println("- 输入'" + CANCEL_MARKER + "'取消当前输入");
        System.out.println("- 输入'exit'退出程序");
        
        Scanner scanner = new Scanner(System.in);
        
        while (true) {
            StringBuilder inputBuilder = new StringBuilder();
            boolean isMultiLine = false;
            
            System.out.print("\nUser: ");
            
            // 读取第一行
            String line = scanner.nextLine().trim();
            
            // 检查是否退出
            if ("exit".equalsIgnoreCase(line)) {
                break;
            }
            
            // 普通单行模式
            inputBuilder.append(line);
            
            // 如果第一行不为空且不包含结束标记，启用多行模式
            if (!line.isEmpty() && !line.equals(END_MARKER)) {
                if (line.equals(CANCEL_MARKER)) {
                    System.out.println("已取消当前输入。");
                    continue;
                }
                
                // 检查输入结束标记
                while (!line.equals(END_MARKER)) {
                    // 等待下一行输入
                    System.out.print("> ");
                    line = scanner.nextLine().trim();
                    
                    // 如果输入取消标记，取消整个输入
                    if (line.equals(CANCEL_MARKER)) {
                        inputBuilder.setLength(0); // 清空输入
                        System.out.println("已取消当前输入。");
                        break;
                    }
                    
                    // 如果不是结束标记，添加到输入中
                    if (!line.equals(END_MARKER)) {
                        // 添加换行符和当前行
                        inputBuilder.append("\n").append(line);
                        isMultiLine = true;
                    }
                }
            }
            
            // 如果输入被取消，继续下一轮
            if (inputBuilder.length() == 0) {
                continue;
            }
            
            final String input = inputBuilder.toString();
            
            // 显示实际处理的输入（多行模式下）
            if (isMultiLine) {
                System.out.println("\n处理多行输入：\n---\n" + input + "\n---");
            }
            
            // 创建回调处理响应
            client.chat(input, new ChatCallback() {
                @Override
                public void onAiResponse(String text) {
                    System.out.println("\nAssistant: " + text);
                }
                
                @Override
                public void onToolUse(String toolName, String parameters) {
                    System.out.println("\n[使用工具] " + toolName);
                    System.out.println("参数: " + parameters);
                }
                
                @Override
                public void onToolResult(String toolName, String result) {
                    System.out.println("[工具结果] " + toolName + ": " + result);
                }
                
                @Override
                public void onFinished() {
                    System.out.println();
                }
                
                @Override
                public void onError(String errorMessage) {
                    System.err.println("\n错误: " + errorMessage);
                }
            });
        }
        
        client.close();
        System.out.println("感谢使用，再见！");
        scanner.close();
    }
} 