package org.yubang.util.mcpdemo.example;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 简单的MCP客户端
 * 用于连接MCP服务器并获取工具列表
 */
public class SimpleMcpClient {

    private static final ObjectMapper objectMapper = new ObjectMapper();
    
    public static void main(String[] args) {
        try {
            // 从JAR包获取工具列表
            List<Map<String, Object>> tools = getToolsFromMcpServer("localhost", 9507);
            
            // 打印工具信息
            System.out.println("从MCP服务器获取的工具列表:");
            for (Map<String, Object> tool : tools) {
                System.out.println("工具名称: " + tool.get("name"));
                System.out.println("工具描述: " + tool.get("description"));
                
                // 打印参数信息
                @SuppressWarnings("unchecked")
                Map<String, Object> parameters = (Map<String, Object>) tool.get("parameters");
                if (parameters != null && !parameters.isEmpty()) {
                    System.out.println("参数信息:");
                    for (Map.Entry<String, Object> param : parameters.entrySet()) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> paramDetails = (Map<String, Object>) param.getValue();
                        System.out.println("  - " + param.getKey() + ": " + 
                            paramDetails.get("description"));
                    }
                }
                System.out.println();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    /**
     * 从MCP服务器获取工具列表
     */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> getToolsFromMcpServer(String host, int port) 
            throws IOException {
        try (Socket socket = new Socket(host, port)) {
            // 准备输入输出流
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
            
            // 读取服务器信息
            String serverInfo = reader.readLine();
            System.out.println("服务器信息: " + serverInfo);
            
            // 创建并发送请求
            String requestId = UUID.randomUUID().toString();
            ObjectNode request = objectMapper.createObjectNode();
            request.put("id", requestId);
            
            ObjectNode instruction = objectMapper.createObjectNode();
            instruction.set("content", objectMapper.createObjectNode());
            request.set("instruction", instruction);
            
            String requestJson = objectMapper.writeValueAsString(request);
            writer.write(requestJson);
            writer.newLine();
            writer.flush();
            
            // 读取响应
            String responseLine = reader.readLine();
            JsonNode responseJson = objectMapper.readTree(responseLine);
            
            // 解析工具信息
            List<Map<String, Object>> toolsList = new ArrayList<>();
            JsonNode toolsNode = responseJson.path("metadata").path("tools");
            
            if (toolsNode.isArray()) {
                for (JsonNode toolNode : toolsNode) {
                    Map<String, Object> toolInfo = new HashMap<>();
                    toolInfo.put("name", toolNode.path("name").asText());
                    toolInfo.put("description", toolNode.path("description").asText());
                    
                    // 解析参数信息
                    Map<String, Object> params = new HashMap<>();
                    JsonNode paramsNode = toolNode.path("parameters");
                    if (paramsNode.isObject()) {
                        paramsNode.fields().forEachRemaining(entry -> {
                            Map<String, Object> paramDetails = new HashMap<>();
                            JsonNode paramNode = entry.getValue();
                            paramDetails.put("description", paramNode.path("description").asText());
                            paramDetails.put("type", paramNode.path("type").asText());
                            params.put(entry.getKey(), paramDetails);
                        });
                    }
                    toolInfo.put("parameters", params);
                    
                    toolsList.add(toolInfo);
                }
            }
            
            return toolsList;
        }
    }
} 