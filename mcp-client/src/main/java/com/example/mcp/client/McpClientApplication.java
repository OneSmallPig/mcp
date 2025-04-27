package com.example.mcp.client;

import com.example.mcp.client.config.McpClientConfig;
import com.example.mcp.client.model.Message;
import com.example.mcp.client.model.Tool;
import com.example.mcp.client.model.ToolCall;
import com.example.mcp.client.service.McpSseService;
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
            if (args[0].equals("--sse")) {
                // SSE交互模式 - 需要先确保本地服务器已启动
                runSseInteractiveMode(new McpSseServiceImpl());
            }
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
                new McpServiceImpl();

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
     * 运行SSE交互模式
     * 
     * @param mcpSseService MCP SSE服务
     */
    private static void runSseInteractiveMode(McpSseService mcpSseService) {
        Scanner scanner = new Scanner(System.in);
        List<Message> messages = new ArrayList<>();
        final String END_MARKER = "/end";
        final String CANCEL_MARKER = "/cancel";
        
        System.out.println("欢迎使用MCP客户端SSE模式！");
        System.out.println("- 输入'" + END_MARKER + "'结束多行输入并发送消息");
        System.out.println("- 输入词语后跟'" + END_MARKER + "'(例如：'你好/end')可以直接发送");
        System.out.println("- 输入'" + CANCEL_MARKER + "'取消当前输入");
        System.out.println("- 输入'exit'退出程序");
        System.out.println("你可以直接输入单行文本，或使用回车输入多行文本，以'" + END_MARKER + "'结束。");
        
        // 获取可用工具信息
        List<Tool> availableTools = McpClientConfig.getInstance().getTools();
        StringBuilder toolPrompt = new StringBuilder();
        if (!availableTools.isEmpty()) {
            toolPrompt.append("系统仅有以下工具可用，没有其他工具：\n");
            for (Tool tool : availableTools) {
                toolPrompt.append("- ").append(tool.getName()).append(": ").append(tool.getDescription()).append("\n");
            }
            toolPrompt.append("\n严格遵守以下规则：\n");
            toolPrompt.append("1. 只有当用户明确要求的功能与上述工具的具体用途完全匹配时，才调用相应工具。\n");
            toolPrompt.append("2. 如果用户询问的内容（如天气、新闻等）没有对应的专门工具，直接使用你的知识以纯文本方式回答，不要提及任何工具或API。\n");
            toolPrompt.append("3. 不要建议使用API接口、数据库查询或任何工具作为替代方案，也不要提示用户提供API配置、数据库连接等信息。\n");
            toolPrompt.append("4. 对于没有工具支持的功能（如天气查询），请直接回答问题本身，就像一个没有工具能力的助手一样，完全不要提及工具话题。\n");
            toolPrompt.append("5. 不要以任何形式暗示用户可以通过提供配置信息来使用现有工具实现其他功能。\n");
        } else {
            toolPrompt.append("当前系统没有配置任何工具。请仅提供文本回复，不要提及或尝试调用任何工具。");
        }
        
        // 添加系统提示
        messages.add(new Message("system", 
            "你是一个帮助助手。请严格遵循以下指示：" + toolPrompt));
        
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
            
            // 检查行尾是否有结束标记
            if (line.endsWith(END_MARKER) && !line.equals(END_MARKER)) {
                // 移除结束标记，保留实际内容
                String content = line.substring(0, line.length() - END_MARKER.length());
                inputBuilder.append(content);
            } else {
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
                        
                        // 检查行尾是否有结束标记
                        if (line.endsWith(END_MARKER) && !line.equals(END_MARKER)) {
                            // 移除结束标记，保留实际内容
                            String content = line.substring(0, line.length() - END_MARKER.length());
                            inputBuilder.append("\n").append(content);
                            isMultiLine = true;
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
            }
            
            // 如果输入被取消，继续下一轮
            if (inputBuilder.length() == 0) {
                continue;
            }
            
            String input = inputBuilder.toString();
            
            // 显示实际处理的输入（多行模式下）
            if (isMultiLine) {
                System.out.println("\n处理多行输入：\n---\n" + input + "\n---");
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

} 