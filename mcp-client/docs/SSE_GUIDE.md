# MCP客户端 SSE模式使用指南

## 简介

SSE（Server-Sent Events）是一种服务器推送技术，使服务器能够实时向客户端推送数据。在MCP客户端中，SSE模式允许大模型生成的内容实时显示，同时提供工具调用的实时反馈，极大地提升了用户体验。

## SSE模式的优势

1. **实时显示**：AI生成的内容会实时显示，而不需要等待完整响应
2. **工具调用可视化**：当AI决定调用工具时，用户可以看到调用过程和结果
3. **改善交互体验**：对于长回答或复杂工具调用，用户无需长时间等待
4. **透明的处理流程**：用户能够清晰地看到AI的思考和处理过程

## SSE事件类型

在MCP客户端中，我们定义了以下SSE事件类型：

- **TEXT_CHUNK**：模型生成的文本片段
- **TOOL_CALL**：模型请求调用工具
- **TOOL_RESULT**：工具执行结果
- **FINISHED**：交互完成
- **ERROR**：发生错误

## 配置SSE服务器

要使用SSE模式，需要配置支持SSE的MCP服务器。有以下几种配置方式：

### 1. 远程SSE服务器配置

可以通过以下方式配置远程SSE服务器：

#### 配置文件方式
修改 `src/main/resources/application.yml` 文件：

```yaml
mcp:
  client:
    sse:
      serverUrl: http://your-sse-server-address:8080
      serverPath: api/chat/stream
      useSse: true
```

#### 外部JSON配置文件方式
创建或编辑 `mcp.json` 文件：

```json
{
  "sse": {
    "serverUrl": "http://your-sse-server-address:8080",
    "serverPath": "api/chat/stream",
    "useSse": true
  }
}
```

#### 环境变量方式

```bash
export MCP_SSE_SERVER_URL=http://your-sse-server-address:8080
export MCP_SSE_SERVER_PATH=api/chat/stream
export MCP_USE_SSE=true
```

### 2. 本地MCP服务器配置 (新增功能)

SSE模式现在支持自动使用本地MCP服务器！如果你在 `mcp.json` 中配置了本地MCP服务器，客户端会自动设置SSE服务器使用同一个本地服务器：

```json
{
  "McpServer": {
    "command": "java",
    "args": ["-jar", "path/to/mcp-server.jar"]
  }
}
```

无需额外配置，系统会：
1. 自动检测本地MCP服务器配置
2. 在SSE模式启动前确保本地服务器已运行
3. 将SSE服务器URL设置为与MCP服务器相同
4. 使用默认SSE路径 `api/chat/stream` (除非另有指定)

这使得你可以在本地环境中轻松体验流式响应和实时工具调用的全部功能！

## 运行SSE模式

有两种方式运行SSE模式：

### 1. 标准SSE模式

```bash
java -jar target/mcp-client-1.0-SNAPSHOT-jar-with-dependencies.jar --sse
```

这种模式适合开发者使用，会显示更多详细信息。

### 2. 简化SSE模式（适合初学者）

```bash
java -jar target/mcp-client-1.0-SNAPSHOT-jar-with-dependencies.jar --simple
```

这种模式专为初学者设计，界面更加友好，隐藏了复杂的技术细节。

## 示例交互

### 标准SSE模式示例：

```
欢迎使用MCP客户端（SSE流式模式）！输入'exit'退出。

User: 请帮我查询北京今天的天气 