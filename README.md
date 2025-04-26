# MCP (Model Completion Protocol) 客户端与服务器

本项目包含MCP协议的客户端与服务器实现，可以让服务端的MCP工具被客户端正确调用。

## 项目结构

- `mcp-client/`: MCP客户端实现
- `mcp-server/`: MCP服务器示例实现

## MCP协议介绍

MCP (Model Completion Protocol) 是一种用于与大型语言模型 (LLM) 和其工具进行通信的协议。与HTTP REST API不同，MCP协议支持：

1. 通过TCP连接进行通信
2. 原生工具调用支持
3. 更高效的通信机制

## 功能特性

- 支持通过HTTP REST API或MCP协议调用服务器
- 自动发现和调用服务器提供的工具
- 支持本地和远程MCP服务器
- 工具调用结果自动处理

## 配置

### 客户端配置

在`application.yml`中配置：

```yaml
mcp:
  client:
    server:
      url: http://localhost:9507   # 服务器URL
      path: api/chat               # API路径
      useMcpProtocol: true         # 是否使用MCP协议
```

也可以通过环境变量配置:

```
MCP_SERVER_URL=http://localhost:9507
MCP_SERVER_PATH=api/chat
MCP_USE_PROTOCOL=true
```

### 服务器配置

在`application.properties`中配置:

```properties
# MCP
spring.ai.mcp.server.name=mcp-database-api-tools
spring.ai.mcp.server.version=1.0.0
spring.ai.mcp.server.type=SYNC
spring.ai.mcp.server.stdio=true
spring.ai.mcp.server.tcp=true
```

## 使用方式

### 客户端调用

```java
// 获取MCP服务实例
McpService mcpService = new McpServiceImpl();

// 创建聊天请求
ChatRequest request = new ChatRequest();
request.setModel("deepseek-r1-250120");

// 设置消息
List<Message> messages = new ArrayList<>();
messages.add(new Message("user", "你好，我想查询天气"));
request.setMessages(messages);

// 发送请求
ChatResponse response = mcpService.sendChatRequest(request);

// 输出结果
System.out.println(response.getChoices().get(0).getMessage().getContent());
```

### 服务器工具实现

使用Spring AI的`@Tool`注解标注工具方法:

```java
@Service
public class WeatherService {
    
    @Tool(name = "get_weather", description = "获取指定城市的天气信息")
    public String getWeather(String city) {
        // 实现天气查询逻辑
        return city + "的天气：晴天，25°C";
    }
}
```

## MCP协议通信流程

1. 客户端通过TCP连接到MCP服务器
2. 客户端发送请求消息
3. 服务器处理请求并返回响应
4. 如果响应包含工具调用，客户端通过MCP协议调用工具
5. 工具调用结果被添加到对话历史
6. 客户端继续与服务器交互直到获得最终响应

## 贡献

欢迎提交问题和改进建议！ 