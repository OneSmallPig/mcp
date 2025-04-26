# MCP 客户端 - 火山引擎 DeepSeek 服务

这个项目是一个对接火山引擎 DeepSeek 服务的 MCP (Model Communication Protocol) 客户端，支持直接调用火山引擎 API 或通过 MCP 服务器调用。

## 功能特点

- 支持直接调用火山引擎 DeepSeek 服务 API
- 支持通过远程 MCP 服务器调用 API
- 支持通过本地 MCP 服务器调用 API（自动启动和管理）
- 支持自动从 MCP 服务器获取工具列表（包括HTTP接口和JAR命令行方式）
- 支持通过原生MCP协议获取服务器工具列表
- 支持从Spring Boot应用自动发现工具列表
- 支持外部 JSON 配置文件配置
- 提供交互式使用模式和演示模式
- 完整的日志记录和错误处理
- 支持配置文件和环境变量配置

## 技术栈

- Java 11+
- Maven
- OkHttp (HTTP 客户端)
- Gson (JSON 处理)
- SLF4J + Logback (日志)

## 快速开始

### 前提条件

- JDK 11 或更高版本
- Maven 3.6 或更高版本
- 火山引擎账号和 DeepSeek 服务的 API 密钥

### 密钥配置

有三种方式配置火山引擎的 API 密钥：

1. **配置文件方式**：修改 `src/main/resources/application.yml` 文件

```yaml
mcp:
  client:
    volcano:
      apiKey: your_api_key_here
```

2. **外部 JSON 配置文件方式**：创建 `mcp.json` 文件

```json
{
  "apiKey": "your_api_key_here"
}
```

3. **环境变量方式**（推荐用于生产环境）：

```bash
# 设置环境变量
export VOLCANO_API_KEY=your_api_key_here
export VOLCANO_ENDPOINT=https://ark.cn-beijing.volces.com/api/v3/chat/completions
export VOLCANO_MODEL=deepseek-r1-250120
```

### MCP 服务器配置

如果要连接到 MCP 服务器，可以通过以下方式配置：

1. **远程 MCP 服务器**：修改 `src/main/resources/application.yml` 文件

```yaml
mcp:
  client:
    server:
      url: http://your-mcp-server-address:8080
      path: api/chat
```

或在 `mcp.json` 中配置：

```json
{
  "server": {
    "url": "http://your-mcp-server-address:8080",
    "path": "api/chat"
  }
}
```

2. **本地 MCP 服务器 (JAR方式)**：在 `mcp.json` 中配置JAR文件路径

```json
{
  "McpServer": {
    "command": "path/to/mcp-server.jar"
  }
}
```

或配置命令行方式：

```json
{
  "McpServer": {
    "command": "java",
    "args": ["-jar", "path/to/mcp-server.jar"]
  }
}
```

3. **环境变量方式**：

```bash
export MCP_SERVER_URL=http://your-mcp-server-address:8080
export MCP_SERVER_PATH=api/chat
```

### 工具列表获取

客户端支持多种方式自动从 MCP 服务器获取工具列表，无需手动配置：

#### MCP原生协议

对于兼容MCP协议的服务器，客户端会首先尝试通过原生MCP协议直接获取工具列表：

1. 客户端会连接服务器的Socket端口
2. 发送MCP协议格式的工具发现请求
3. 解析从metadata.tools字段中返回的工具信息

这种方式特别适用于通过JAR包启动的MCP服务器，例如你提供的代码示例中的服务器实现。

#### Spring Boot应用工具发现

如果MCP原生协议获取失败，客户端会尝试从以下HTTP端点获取工具列表：

- `/api/tools`
- `/tools`
- `/api/functions`
- `/functions`
- `/ai/tools`
- `/springai/tools`
- `/actuator/tools`
- `/actuator/ai/tools`

对于使用Spring AI注解注册的工具，它们通常会通过上述端点之一被暴露出来。客户端还会尝试从Spring Boot Actuator信息端点获取工具信息。

#### 默认工具

如果无法从服务器获取工具列表，客户端会自动注册一些默认工具，确保基本功能可用。当然，你也可以在`mcp.json`中手动配置工具：

```json
{
  "tools": [
    {
      "name": "get_weather",
      "description": "获取指定城市的天气信息",
      "parameters": {
        "type": "object",
        "properties": {
          "city": {
            "type": "string",
            "description": "城市名称"
          }
        },
        "required": ["city"]
      }
    }
  ]
}
```

### 编译构建

```bash
# 编译打包
mvn clean package

# 打包后会在 target 目录生成可执行 JAR 文件
# 例如: target/mcp-client-1.0-SNAPSHOT-jar-with-dependencies.jar
```

### 运行客户端

```bash
# 演示模式
java -jar target/mcp-client-1.0-SNAPSHOT-jar-with-dependencies.jar

# 交互模式
java -jar target/mcp-client-1.0-SNAPSHOT-jar-with-dependencies.jar --interactive
```

## 客户端交互

### 交互模式

当以交互模式启动客户端时，你可以与 AI 进行对话：

1. 启动客户端：

```bash
java -jar target/mcp-client-1.0-SNAPSHOT-jar-with-dependencies.jar --interactive
```

2. 输入你的问题或指令：

```
欢迎使用MCP客户端！输入'exit'退出。

User: 你好，请介绍一下自己。 