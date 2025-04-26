package org.yubang.util.mcpdemo.example;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * MCP客户端示例
 * 演示如何通过MCP协议获取工具列表
 */
public class McpClientExample {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    public static void main(String[] args) {
        try {
            // 启动JAR包作为子进程
            Process mcpProcess = startMcpServerProcess();
            
            // 准备标准输入输出流进行通信
            BufferedReader reader = new BufferedReader(new InputStreamReader(mcpProcess.getInputStream()));
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(mcpProcess.getOutputStream()));
            
            // 读取服务器发送的启动信息
            String serverInfo = reader.readLine();
            System.out.println("服务器信息: " + serverInfo);
            
            // 发送工具发现请求
            discoverTools(writer, reader);
            
            // 关闭子进程
            mcpProcess.destroy();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    /**
     * 启动MCP服务器进程
     */
    private static Process startMcpServerProcess() throws IOException {
        ProcessBuilder processBuilder = new ProcessBuilder(
            "java", "-jar", "./target/mcp-demo-0.0.1-SNAPSHOT.jar"
        );
        processBuilder.redirectErrorStream(true);
        
        Process process = processBuilder.start();
        System.out.println("MCP服务器进程已启动");
        
        // 等待服务器启动
        try {
            TimeUnit.SECONDS.sleep(3);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        return process;
    }
    
    /**
     * 发送工具发现请求
     */
    private static void discoverTools(BufferedWriter writer, BufferedReader reader) throws IOException {
        // 创建MCP请求
        String requestId = UUID.randomUUID().toString();
        
        // 构建简单的请求JSON
        ObjectNode request = objectMapper.createObjectNode();
        request.put("id", requestId);
        
        ObjectNode instruction = objectMapper.createObjectNode();
        instruction.set("content", objectMapper.createObjectNode());
        request.set("instruction", instruction);
        
        // 序列化请求并发送
        String requestJson = objectMapper.writeValueAsString(request);
        System.out.println("发送请求: " + requestJson);
        writer.write(requestJson);
        writer.newLine();
        writer.flush();
        
        // 读取响应
        String responseLine = reader.readLine();
        System.out.println("收到响应: " + responseLine);
        
        // 解析响应中的工具信息
        try {
            JsonNode responseJson = objectMapper.readTree(responseLine);
            JsonNode toolsNode = responseJson.path("metadata").path("tools");
            
            System.out.println("\n可用工具列表:");
            if (toolsNode.isArray()) {
                for (JsonNode toolNode : toolsNode) {
                    System.out.println("工具名称: " + toolNode.path("name").asText());
                    System.out.println("工具描述: " + toolNode.path("description").asText());
                    
                    JsonNode paramsNode = toolNode.path("parameters");
                    System.out.println("参数信息: ");
                    if (paramsNode.isObject()) {
                        paramsNode.fieldNames().forEachRemaining(field -> {
                            System.out.println("  - " + field + ": " + 
                                paramsNode.path(field).path("description").asText());
                        });
                    }
                    System.out.println();
                }
            }
        } catch (Exception e) {
            System.out.println("解析响应失败: " + e.getMessage());
        }
    }
} 