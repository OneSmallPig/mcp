# MCP客户端 开发者指南

## 架构概述

MCP客户端采用了模块化设计，主要由以下组件构成：

1. **核心接口层**：定义了与MCP服务器和大模型交互的基本接口
2. **通信层**：负责HTTP通信、SSE流式通信等功能
3. **服务实现层**：实现与火山引擎和MCP服务器的交互逻辑
4. **模型层**：定义请求和响应的数据结构
5. **配置管理**：处理各种配置选项和环境变量
6. **用户界面**：提供命令行交互界面

## SSE流式通信实现

SSE（Server-Sent Events）流式通信是本客户端的核心特性，它允许大模型调用MCP工具，并实时展示整个交互过程。关键组件包括：

- `McpSseService`：SSE服务接口
- `McpSseServiceImpl`：SSE服务实现
- `SseEventListener`：SSE事件监听器接口
- `SimpleSseEventListener`：简单的事件监听器实现

### 事件流程

1. 客户端发送请求到服务器
2. 服务器将请求转发给大模型
3. 大模型生成内容并可能调用工具
4. 服务器通过SSE事件推送文本片段、工具调用请求等
5. 客户端接收事件并处理（显示文本、执行工具调用等）
6. 客户端将工具结果发送回服务器
7. 大模型继续生成内容

### 自定义SSE实现

如果服务器不支持标准的SSE，我们提供了自定义的SSE实现：

- `EventSource`：事件源接口
- `EventSourceListener`：事件监听器基类
- `EventSources`：事件源工厂类

## 工具集成

为了让大模型能够调用MCP工具，我们实现了以下机制：

1. **工具发现**：客户端可以从服务器自动发现可用工具
2. **工具调用**：当收到TOOL_CALL事件时，客户端执行相应的工具
3. **结果反馈**：将工具执行结果发送回服务器，以便大模型继续生成内容

## 简化模式

为了便于初学者使用，我们提供了一个简化模式：

- `SimpleMcpClient`：简化的客户端实现
- `ChatCallback`：简单的回调接口，处理AI响应和工具调用

## 自定义MCP服务器适配

如果你想将客户端与自定义的MCP服务器集成，需要做以下工作：

1. 确保服务器支持SSE（或实现类似的流式响应）
2. 实现以下API端点：
   - `/api/chat/stream`：接收聊天请求并返回SSE流
   - `/api/chat/tool-result`：接收工具执行结果
   - `/api/tools`：提供工具列表
3. 使SSE事件格式与客户端兼容

## 配置示例

以下是一个完整的配置示例，包括火山引擎、MCP服务器和SSE设置：

**application.yml**:
```yaml
mcp:
  client:
    volcano:
      apiKey: your_api_key_here
      deepseek:
        endpoint: https://ark.cn-beijing.volces.com/api/v3/chat/completions
        model: deepseek-r1-250120
      request:
        timeout: 60
        retries: 3
    server:
      url: http://localhost:8080
      path: api/chat
      useMcpProtocol: false
    sse:
      serverUrl: http://localhost:8080
      serverPath: api/chat/stream
      useSse: true
```

## 扩展指南

### 添加新的大模型提供商

1. 创建新的客户端类，如 `NewModelClient.java`
2. 实现请求和响应的转换逻辑
3. 更新配置类以支持新的提供商

### 添加新的工具执行方式

1. 在 `McpSseServiceImpl` 中扩展 `executeToolCall` 方法
2. 添加新的工具处理逻辑
3. 更新工具结果处理机制

## 调试技巧

1. 使用日志跟踪请求和响应：
```java
log.debug("发送请求: {}", requestBody);
```

2. 监控SSE事件：
```java
log.debug("收到SSE事件: type={}, data={}", type, data);
```

3. 启用详细日志：
在 `logback.xml` 中设置：
```xml
<logger name="com.example.mcp.client" level="DEBUG"/>
```

## 社区贡献

欢迎参与项目开发和改进！您可以通过以下方式贡献：

1. 提交Bug报告和功能请求
2. 开发新特性或修复问题
3. 改进文档和示例
4. 分享您的使用经验

希望这个指南能帮助您更好地理解和扩展MCP客户端！ 