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
4. 基于SSE的实时推送机制

## 功能特性

- 支持通过HTTP REST API或MCP协议调用服务器
- 支持通过SSE (Server-Sent Events) 建立长连接，实时接收服务器推送的消息
- 自动发现和调用服务器提供的工具
- 支持模型调用MCP工具辅助处理
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
      useSse: true                 # 是否使用SSE长连接
```

也可以通过环境变量配置:

```
MCP_SERVER_URL=http://localhost:9507
MCP_SERVER_PATH=api/chat
MCP_USE_PROTOCOL=true
MCP_USE_SSE=true
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
spring.ai.mcp.server.sse=true
```

## 使用方式

### 客户端调用 (传统方式)

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

### 客户端调用 (SSE方式)

```java
// 创建SSE服务
McpSseService sseService = new McpSseServiceImpl();

// 创建SSE事件监听器
SseEventListener listener = new SimpleSseEventListener() {
    @Override
    public void onTextChunk(String text) {
        System.out.print(text); // 直接打印到控制台，模拟流式输出
    }
    
    @Override
    public void onToolCall(ToolCall toolCall) {
        System.out.println("\n[调用工具: " + toolCall.getFunction() + "]");
    }
    
    @Override
    public void onToolResult(String function, String result) {
        System.out.println("[工具结果: " + result + "]");
    }
    
    @Override
    public void onComplete() {
        System.out.println("\n\n对话完成！");
    }
    
    @Override
    public void onError(Throwable t) {
        System.err.println("发生错误: " + t.getMessage());
    }
};

// 发送提示词，开始对话
sseService.sendPrompt("北京的天气怎么样？", listener);
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

### 使用SSE的通信流程

1. 客户端通过SSE连接到MCP服务器
2. MCP服务器保持连接并为客户端分配一个唯一ID
3. 客户端向大模型发送带有工具描述的对话请求
4. 大模型返回响应，可能包含文本或工具调用请求
5. 如果有工具调用，客户端向MCP服务器发送工具调用请求
6. MCP服务器执行工具并通过SSE返回结果
7. 大模型拿到工具执行结果，继续生成内容
8. 整个过程通过SSE实时推送给客户端

### 传统通信流程

1. 客户端通过TCP连接到MCP服务器
2. 客户端发送请求消息
3. 服务器处理请求并返回响应
4. 如果响应包含工具调用，客户端通过MCP协议调用工具
5. 工具调用结果被添加到对话历史
6. 客户端继续与服务器交互直到获得最终响应

## 贡献

欢迎提交问题和改进建议！ 