package org.yubang.util.mcpdemo.example;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.yubang.util.mcpdemo.model.ToolInfo;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.Scanner;

/**
 * 客户端示例
 * 演示如何获取MCP服务中的工具列表
 */
public class ClientExample {

    public static void main(String[] args) {
        try {
            // 方法1：通过HTTP API获取工具列表
            System.out.println("方法1：通过HTTP API获取工具列表");
            List<ToolInfo> tools = getToolsViaHttp("http://localhost:9507/api/tools");
            for (ToolInfo tool : tools) {
                System.out.println("工具名称: " + tool.getName());
                System.out.println("工具描述: " + tool.getDescription());
                System.out.println("方法名: " + tool.getMethodName());
                System.out.println("类名: " + tool.getClassName());
                System.out.println();
            }
            
            // 方法2：通过MCP协议获取工具列表
            System.out.println("方法2：通过MCP协议获取工具列表");
            System.out.println("注意：这需要通过MCP协议处理，详见Spring AI文档中的MCP客户端实现");
            System.out.println("可以参考：https://docs.spring.io/spring-ai/reference/api/clients/mcp-client.html");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    /**
     * 通过HTTP API获取工具列表
     */
    private static List<ToolInfo> getToolsViaHttp(String urlStr) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        connection.connect();
        
        int responseCode = connection.getResponseCode();
        if (responseCode != 200) {
            throw new RuntimeException("请求失败，状态码: " + responseCode);
        }
        
        StringBuilder response = new StringBuilder();
        Scanner scanner = new Scanner(connection.getInputStream());
        while (scanner.hasNextLine()) {
            response.append(scanner.nextLine());
        }
        scanner.close();
        
        ObjectMapper mapper = new ObjectMapper();
        return mapper.readValue(response.toString(), new TypeReference<List<ToolInfo>>() {});
    }
} 