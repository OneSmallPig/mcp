# MCP 数据库和API工具

这是一个基于Spring Boot和Spring AI的MCP（Model Context Protocol）服务器项目，提供了三个强大的工具：

1. 数据库查询工具：可以配置数据库连接信息，查询指定数据表的数据
2. API调用工具：可以配置API信息，调用外部接口获取数据
3. Excel导出工具：可以将数据或查询结果导出为Excel表格格式

## 功能特点

- 支持动态配置数据库连接（支持MySQL等多种数据库）
- 支持自定义SQL查询或表级查询
- 支持多种HTTP请求方法（GET、POST、PUT、DELETE等）
- 支持自定义请求头、请求体和URL参数
- 支持将数据导出为Excel格式，并支持自定义表头和文件名
- 通过MCP协议与AI模型无缝集成

## 使用方法

### 数据库查询工具

数据库查询工具允许你配置数据库连接信息，查询指定数据表的数据。

使用示例配置：

```json
{
  "url": "jdbc:mysql://localhost:3306/your_database",
  "username": "your_username",
  "password": "your_password",
  "tableName": "your_table"
}
```

或使用自定义SQL：

```json
{
  "url": "jdbc:mysql://localhost:3306/your_database",
  "username": "your_username",
  "password": "your_password",
  "sql": "SELECT * FROM your_table WHERE your_column = 'value'"
}
```

### API调用工具

API调用工具允许你配置API信息，调用外部接口获取数据。

GET请求示例配置：

```json
{
  "url": "https://api.example.com/data",
  "method": "GET",
  "headers": {
    "Content-Type": "application/json",
    "Authorization": "Bearer your_token"
  },
  "params": {
    "param1": "value1",
    "param2": "value2"
  },
  "connectTimeout": 5000,
  "readTimeout": 5000
}
```

POST请求示例：

```json
{
  "url": "https://api.example.com/data",
  "method": "POST",
  "headers": {
    "Content-Type": "application/json"
  },
  "body": "{\"key\": \"value\"}",
  "connectTimeout": 5000,
  "readTimeout": 5000
}
```

### Excel导出工具

Excel导出工具允许你将数据导出为Excel表格格式，支持直接提供数据或从数据库查询获取数据。

直接提供数据导出示例：

```json
{
  "data": [
    {"id": 1, "name": "张三", "age": 25},
    {"id": 2, "name": "李四", "age": 30}
  ],
  "fileName": "用户数据",
  "sheetName": "用户信息",
  "headerMapping": {
    "id": "编号",
    "name": "姓名",
    "age": "年龄"
  },
  "returnBase64": true
}
```

从数据库查询并导出示例：

```json
{
  "sql": "SELECT * FROM users WHERE age > 18",
  "fileName": "成年用户",
  "sheetName": "用户列表",
  "databaseConfig": {
    "url": "jdbc:mysql://localhost:3306/your_database",
    "username": "your_username",
    "password": "your_password"
  },
  "headerMapping": {
    "id": "用户ID",
    "name": "用户名",
    "age": "年龄",
    "email": "电子邮箱"
  }
}
```

## 启动服务

```bash
./mvnw spring-boot:run
```

服务将在标准输入输出上启用MCP协议，可与支持MCP的AI模型或客户端集成。

## 自定义配置

配置文件位于`src/main/resources/application.properties`，可根据需要调整：

```properties
# MCP服务器配置
spring.ai.mcp.server.name=mcp-database-api-tools
spring.ai.mcp.server.version=1.0.0
spring.ai.mcp.server.type=SYNC
spring.ai.mcp.server.stdio=true
spring.ai.mcp.server.tcp=true
```

## 安全注意事项

- 请不要在公开环境中存储敏感的数据库凭据
- 建议在生产环境中配置适当的访问控制和安全措施
- 定期更新数据库密码和API令牌 

## 获取MCP工具列表

当将该MCP服务以JAR形式提供给MCP客户端时，有多种方式可以获取所有工具信息：

### 1. 通过HTTP API获取

项目提供了RESTful API接口，可以通过HTTP请求获取工具列表：

```bash
# 获取完整工具信息
curl http://localhost:9507/api/tools

# 获取简化版工具信息
curl http://localhost:9507/api/mcp/tools/simple
```

### 2. 通过MCP协议获取

使用MCP协议连接服务器，在初始连接时会自动返回包含工具信息的元数据：

```java
// 示例代码
SimpleMcpClient.getToolsFromMcpServer("localhost", 9507);
```

### 3. 通过JAR包反射获取

如果将JAR包作为依赖引入到其他项目中，可以通过Java反射机制获取工具信息：

```java
import org.springframework.ai.tool.annotation.Tool;
import java.lang.reflect.Method;

// 反射获取所有标记了@Tool注解的方法
for (Method method : serviceClass.getMethods()) {
    if (method.isAnnotationPresent(Tool.class)) {
        Tool annotation = method.getAnnotation(Tool.class);
        String toolName = annotation.name();
        String description = annotation.description();
        // 处理工具信息...
    }
}
```

完整示例可参考项目中的 `src/main/java/org/yubang/util/mcpdemo/example` 目录下的示例代码。 