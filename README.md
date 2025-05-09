# MCP (Model Completion Protocol) 客户端与服务器

本项目包含MCP协议的客户端与服务器实现，可以让服务端的MCP工具被客户端正确调用。

## 一、项目结构

- `mcp-client/`: MCP客户端实现
- `mcp-server/`: MCP服务端实现

## 二、MCP协议介绍

MCP (Model Completion Protocol) 是一种用于与大型语言模型 (LLM) 和其工具进行通信的协议。与HTTP REST API不同，MCP协议支持：

1. 通过TCP连接进行通信
2. 原生工具调用支持
3. 更高效的通信机制
4. 基于SSE的实时推送机制

## 三、技术选型

### 1、客户端技术栈

- **Java 11+**: 核心开发语言
- **OkHttp (4.9.3)**: HTTP客户端，用于API请求和SSE连接
- **Gson (2.8.9)**: JSON序列化和反序列化
- **SLF4J & Logback**: 日志框架
- **SnakeYAML**: 配置文件解析
- **Hutool**: 实用工具集合

### 2、服务器技术栈

- **Java 21**: 开发语言
- **Spring Boot 3.4.4**: 应用开发框架
- **Spring AI 1.0.0-M7**: Spring AI框架，提供MCP服务器支持
- **Spring WebFlux**: 反应式编程框架，用于异步API调用
- **WebClient**: 非阻塞HTTP客户端
- **MySQL Connector**: 数据库连接
- **HikariCP**: 高性能连接池
- **Apache POI**: Excel文件处理
- **Jackson/Gson**: JSON处理库
- **Lombok**: 减少模板代码

## 四、功能特性

- 支持通过HTTP REST API或MCP协议调用服务器
- 支持通过SSE (Server-Sent Events) 建立长连接，实时接收服务器推送的消息
- 自动发现和调用服务器提供的工具
- 支持模型调用MCP工具辅助处理
- 支持本地和远程MCP服务器
- 工具调用结果自动处理

## 五、实现步骤

### 1. 客户端实现

1. **配置管理**：读取并解析YAML配置文件或环境变量
2. **HTTP客户端**：使用OkHttp创建HTTP客户端，用于REST API调用
3. **SSE实现**：基于OkHttp创建SSE客户端，处理实时消息推送
4. **事件监听器**：实现事件监听接口，处理来自服务器的各类事件
5. **工具调用**：实现工具调用逻辑，将模型请求转发给MCP服务器
6. **响应处理**：解析服务器响应，处理工具调用结果

### 2. 服务器实现

1. **配置服务器**：设置MCP服务器的基本参数（名称、版本等）
2. **工具注册**：使用Spring AI的@Tool注解注册工具方法
3. **SSE连接管理**：实现SSE连接的创建、维护和清理
4. **工具执行**：实现工具调用的执行和结果返回逻辑
5. **异常处理**：完善错误处理和日志记录

### 3. 协议集成

1. **TCP实现**：支持基于TCP的MCP协议通信
2. **SSE实现**：支持基于SSE的实时消息推送
3. **工具发现**：实现工具自动发现和注册机制

## 六、配置

### 1、客户端配置

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

### 2、服务器配置

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

## 七、关键点

### 1. SSE连接管理

- **心跳机制**：定期发送心跳消息保持连接活跃
- **超时处理**：自动清理超时的SSE连接
- **异常处理**：妥善处理连接异常，避免资源泄漏

#### 实现代码

服务端SSE连接管理核心实现：

```java
@Service
public class SseEmitterService {
    // 默认SSE连接过期时间：12小时
    private static final long DEFAULT_TIMEOUT = 12 * 60 * 60 * 1000;
    
    // 心跳间隔：30秒
    private static final long HEARTBEAT_INTERVAL = 30 * 1000;
    
    // 使用ConcurrentHashMap存储SSE发射器
    private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();
    
    @PostConstruct
    public void init() {
        heartbeatExecutor = Executors.newSingleThreadScheduledExecutor();
        // 启动定时心跳任务
        heartbeatExecutor.scheduleAtFixedRate(
            this::sendHeartbeats, 
            HEARTBEAT_INTERVAL, 
            HEARTBEAT_INTERVAL, 
            TimeUnit.MILLISECONDS
        );
    }
    
    // 创建SSE连接
    public SseEmitter createEmitter(String clientId) {
        // 如果已存在，先移除旧的连接
        removeEmitter(clientId);
        
        SseEmitter emitter = new SseEmitter(DEFAULT_TIMEOUT);
        
        // 添加完成、超时和错误回调
        emitter.onCompletion(() -> removeEmitter(clientId));
        emitter.onTimeout(() -> removeEmitter(clientId));
        emitter.onError((ex) -> removeEmitter(clientId));
        
        emitters.put(clientId, emitter);
        return emitter;
    }
    
    // 发送心跳，保持连接活跃
    private void sendHeartbeats() {
        if (emitters.isEmpty()) {
            return;
        }
        
        Map<String, Object> heartbeatData = Map.of(
            "type", "heartbeat",
            "timestamp", System.currentTimeMillis()
        );
        
        emitters.forEach((clientId, emitter) -> {
            try {
                sendEvent(clientId, "heartbeat", heartbeatData);
            } catch (Exception e) {
                removeEmitter(clientId);
            }
        });
    }
}
```

客户端SSE连接实现：

```java
public class EventSources {
    // 创建EventSource工厂
    public static EventSource.Factory createFactory(OkHttpClient client) {
        return new Factory(client);
    }
    
    // EventSource实现
    static final class RealEventSource implements EventSource {
        @Override
        public void connect() {
            Request request = this.request.newBuilder()
                    .header("Accept", "text/event-stream;charset=UTF-8")
                    .header("Cache-Control", "no-cache")
                    .build();
            
            call = client.newCall(request);
            call.enqueue(new Callback() {
                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    try {
                        listener.onOpen(RealEventSource.this, response);
                        processEvents(response);
                    } catch (Exception e) {
                        if (!canceled) {
                            listener.onFailure(RealEventSource.this, e, response);
                        }
                    } finally {
                        response.close();
                    }
                }
            });
        }
        
        // 处理SSE事件流
        private void processEvents(Response response) throws IOException {
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(response.body().byteStream(), StandardCharsets.UTF_8));
            
            String line;
            StringBuilder dataBuilder = new StringBuilder();
            String lastEventId = "";
            String eventType = "message";
            
            while (!canceled && (line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    // 空行表示事件的结束，处理累积的数据
                    if (dataBuilder.length() > 0) {
                        listener.onEvent(this, lastEventId, eventType, dataBuilder.toString());
                        dataBuilder.setLength(0);
                        eventType = "message"; // 重置为默认事件类型
                    }
                    continue;
                }
                
                // 解析SSE事件格式
                if (line.startsWith("id:")) {
                    lastEventId = line.substring(3).trim();
                } else if (line.startsWith("event:")) {
                    eventType = line.substring(6).trim();
                } else if (line.startsWith("data:")) {
                    dataBuilder.append(line.substring(5).trim());
                    dataBuilder.append("\n");
                }
            }
        }
    }
}
```

### 2. 工具调用流程

- **工具发现**：客户端自动从服务器获取可用工具列表
- **参数处理**：自动处理工具参数的序列化和反序列化
- **异步调用**：支持异步工具调用，不阻塞主流程
- **结果处理**：自动将工具调用结果转发给模型继续处理

#### 实现代码

服务端工具注册与调用：

```java
@SpringBootApplication
public class McpDemoApplication {
    // 注册工具
    @Bean
    public ToolCallbackProvider toolProvider(ApiService apiService, 
                                            DatabaseService databaseService, 
                                            ExcelExportService excelExportService) {
        return MethodToolCallbackProvider.builder()
            .toolObjects(apiService, databaseService, excelExportService)
            .build();
    }
}

// 工具实现示例
@Service
public class ApiService {
    @Tool(name = "API对接", description = "通过配置的API信息，访问对应接口，获取接口返回数据")
    public String callApi(ApiConfig config) {
        // 创建WebClient实例
        WebClient webClient = WebClient.builder()
                .baseUrl(config.getUrl())
                .build();
        
        // 根据请求方法执行不同类型的请求
        WebClient.RequestHeadersSpec<?> requestSpec;
        
        switch (config.getMethod().toUpperCase()) {
            case "POST":
                requestSpec = webClient.post()
                        .uri(uriBuilder -> {
                            config.getParams().forEach(uriBuilder::queryParam);
                            return uriBuilder.build();
                        })
                        .body(BodyInserters.fromValue(config.getBody()));
                break;
            // 其他HTTP方法...
            
            case "GET":
            default:
                requestSpec = webClient.get()
                        .uri(uriBuilder -> {
                            config.getParams().forEach(uriBuilder::queryParam);
                            return uriBuilder.build();
                        });
                break;
        }
        
        // 执行请求并获取响应
        String result = requestSpec.retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofMillis(config.getReadTimeout()))
                .block(Duration.ofMillis(config.getConnectTimeout()));
        
        return result;
    }
}
```

工具执行服务：

```java
@Service
public class ToolExecutionService {
    // 执行工具调用
    public String executeToolCall(String clientId, ToolCallRequest request) {
        String functionName = request.getFunction();
        String argsJson = request.getArguments();
        
        // 根据功能名获取对应的工具
        ToolCallback tool = toolRegistry.getToolByName(functionName);
        if (tool == null) {
            throw new IllegalArgumentException("未找到工具: " + functionName);
        }
        
        try {
            // 解析参数并执行工具方法
            Object result = tool.call(argsJson);
            // 将结果序列化为JSON字符串
            String resultJson = gson.toJson(result);
            
            // 记录工具调用结果
            log.info("工具 {} 执行成功: {}", functionName, resultJson);
            
            // 推送工具调用结果给客户端
            if (sseEmitterService.hasEmitter(clientId)) {
                Map<String, Object> eventData = Map.of(
                    "function", functionName,
                    "result", resultJson
                );
                sseEmitterService.sendEventSafely(clientId, "tool_result", eventData);
            }
            
            return resultJson;
        } catch (Exception e) {
            // 异常处理
            log.error("工具 {} 执行失败: {}", functionName, e.getMessage(), e);
            throw new RuntimeException("工具执行失败: " + e.getMessage(), e);
        }
    }
}
```

客户端工具调用：

```java
public class McpServiceImpl implements McpService {
    @Override
    public ToolCallResult callTool(String function, String arguments) {
        try {
            // 构建工具调用请求
            Map<String, String> requestBody = new HashMap<>();
            requestBody.put("function", function);
            requestBody.put("arguments", arguments);
            
            // 创建HTTP请求
            Request request = new Request.Builder()
                    .url(serverBaseUrl + "/api/tool/execute")
                    .post(RequestBody.create(
                            gson.toJson(requestBody), 
                            MediaType.parse("application/json")))
                    .build();
            
            // 执行请求
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    throw new IOException("工具调用失败: " + response.code());
                }
                
                // 解析响应结果
                String resultJson = Objects.requireNonNull(response.body()).string();
                ToolCallResult result = new ToolCallResult();
                result.setFunction(function);
                result.setResult(resultJson);
                
                return result;
            }
        } catch (Exception e) {
            throw new RuntimeException("工具调用异常: " + e.getMessage(), e);
        }
    }
}
```

### 3. 安全与性能

- **连接池管理**：使用HikariCP管理数据库连接
- **异步与非阻塞**：使用WebClient进行非阻塞API调用
- **资源释放**：确保所有资源（连接、线程等）正确释放
- **错误隔离**：单个工具调用失败不应影响整个系统

#### 实现代码

数据库连接池配置：

```java
@Configuration
public class DatabaseConfig {
    @Bean
    public DataSource dataSource() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:mysql://localhost:3306/mcpdb");
        config.setUsername("mcp_user");
        config.setPassword("password");
        config.setDriverClassName("com.mysql.cj.jdbc.Driver");
        
        // 连接池配置
        config.setMaximumPoolSize(10);
        config.setMinimumIdle(5);
        config.setIdleTimeout(30000);
        config.setConnectionTimeout(10000);
        config.setMaxLifetime(1800000);
        
        // 连接测试查询
        config.setConnectionTestQuery("SELECT 1");
        
        return new HikariDataSource(config);
    }
}
```

资源释放管理：

```java
@Service
public class SseEmitterService {
    // 在服务关闭时释放资源
    @PreDestroy
    public void destroy() {
        if (heartbeatExecutor != null) {
            heartbeatExecutor.shutdownNow();
            log.info("SSE心跳服务已关闭");
        }
        
        // 关闭所有连接
        emitters.forEach((clientId, emitter) -> {
            emitter.complete();
            log.info("关闭客户端 {} 的SSE连接", clientId);
        });
        emitters.clear();
    }
}
```

异步WebClient配置：

```java
@Configuration
public class WebClientConfig {
    @Bean
    public WebClient webClient() {
        return WebClient.builder()
                .codecs(configurer -> configurer
                        .defaultCodecs()
                        .maxInMemorySize(2 * 1024 * 1024)) // 2MB buffer
                .exchangeStrategies(ExchangeStrategies.builder()
                        .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(2 * 1024 * 1024))
                        .build())
                .filter(ExchangeFilterFunction.ofRequestProcessor(
                        clientRequest -> {
                            log.debug("请求: {} {}", 
                                    clientRequest.method(), 
                                    clientRequest.url());
                            return Mono.just(clientRequest);
                        }
                ))
                .build();
    }
}
```

### 4. 扩展性设计

- **工具插件化**：使用Spring Bean和@Tool注解实现工具的即插即用
- **协议兼容性**：同时支持HTTP REST API和MCP协议
- **多种通信模式**：支持SSE长连接和传统请求-响应模式

#### 实现代码

工具插件化实现：

```java
// 使用@Tool注解标记工具方法
@Service
public class DatabaseService {
    @Tool(name = "执行SQL查询", description = "执行SQL查询语句，返回查询结果")
    public List<Map<String, Object>> executeQuery(String sql, Map<String, Object> params) {
        // 实现查询逻辑
        return jdbcTemplate.queryForList(sql, params);
    }
    
    @Tool(name = "执行SQL更新", description = "执行SQL更新语句（INSERT/UPDATE/DELETE），返回影响行数")
    public int executeUpdate(String sql, Map<String, Object> params) {
        // 实现更新逻辑
        return jdbcTemplate.update(sql, params);
    }
}

@Service
public class ExcelExportService {
    @Tool(name = "导出Excel", description = "将数据导出为Excel文件，返回文件下载链接")
    public String exportExcel(String fileName, List<Map<String, Object>> data) {
        // 实现Excel导出逻辑
        return fileService.generateExcelAndGetDownloadLink(fileName, data);
    }
}
```

协议兼容性支持：

```java
@Configuration
public class McpServerConfig {
    @Bean
    public McpServerProperties mcpServerProperties() {
        McpServerProperties properties = new McpServerProperties();
        properties.setName("mcp-database-api-tools");
        properties.setVersion("1.0.0");
        properties.setType(McpServerProperties.ServerType.SYNC);
        
        // 启用多种协议支持
        properties.setStdio(true);
        properties.setTcp(true);
        properties.setSse(true);
        properties.setHttp(true);
        
        return properties;
    }
}
```

多种通信模式支持：

```java
@RestController
@RequestMapping("/api")
public class McpController {
    @PostMapping("/chat")
    public ResponseEntity<ChatResponse> handleChatRequest(@RequestBody ChatRequest request) {
        // 处理传统HTTP请求
        return ResponseEntity.ok(chatService.processRequest(request));
    }
    
    @GetMapping("/chat/stream")
    public SseEmitter handleStreamRequest(@RequestParam String clientId) {
        // 创建SSE连接
        return sseEmitterService.createEmitter(clientId);
    }
    
    @PostMapping("/tool/execute")
    public ResponseEntity<String> executeToolCall(
            @RequestParam String clientId,
            @RequestBody ToolCallRequest request) {
        // 处理工具调用请求
        String result = toolExecutionService.executeToolCall(clientId, request);
        return ResponseEntity.ok(result);
    }
}
```

客户端通信模式选择：

```java
public class McpClient {
    private final McpConfig config;
    private final McpService mcpService;
    private final McpSseService mcpSseService;
    
    public ChatResponse sendRequest(ChatRequest request) {
        if (config.isUseSse()) {
            // 使用SSE长连接模式
            CompletableFuture<ChatResponse> future = new CompletableFuture<>();
            mcpSseService.sendRequest(request, new SseEventListener() {
                private final StringBuilder content = new StringBuilder();
                
                @Override
                public void onTextChunk(String text) {
                    content.append(text);
                }
                
                @Override
                public void onComplete() {
                    ChatResponse response = new ChatResponse();
                    // 设置响应内容
                    future.complete(response);
                }
                
                @Override
                public void onError(Throwable t) {
                    future.completeExceptionally(t);
                }
            });
            return future.join();
        } else {
            // 使用传统HTTP请求
            return mcpService.sendChatRequest(request);
        }
    }
}
```

## 八、使用方式

### 1、客户端调用 (传统方式)

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

### 2、客户端调用 (SSE方式)

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

### 3、服务器工具实现

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

## 九、演示环境

### 1、开发环境

- **JDK**: Java 21 (服务端), Java 11+ (客户端)
- **构建工具**: Maven 3.8+
- **IDE**: IntelliJ IDEA 2023.1+ 或 Visual Studio Code
- **数据库**: MySQL 8.0+ (可选，用于数据库工具演示)

### 2、演示部署

1. **本地部署**
   - 端口: 9509 (服务器默认端口)
   - 启动服务器: `mvn spring-boot:run -f mcp-server/pom.xml`
   - 启动客户端: `mvn exec:java -f mcp-client/pom.xml`

2. **Docker部署** (准备中)
   ```bash
   # 构建镜像
   docker build -t mcp-server:latest -f mcp-server/Dockerfile .
   docker build -t mcp-client:latest -f mcp-client/Dockerfile .
   
   # 运行容器
   docker run -d -p 9509:9509 --name mcp-server mcp-server:latest
   docker run -d --name mcp-client --link mcp-server mcp-client:latest
   ```

3. **演示地址**
   - 服务器API文档: http://localhost:9509/api-docs
   - 测试Web界面: http://localhost:9509/test.html (如果已启用)

## 十、MCP协议通信流程

### 1、使用SSE的通信流程

1. 客户端通过SSE连接到MCP服务器
2. MCP服务器保持连接并为客户端分配一个唯一ID
3. 客户端向大模型发送带有工具描述的对话请求
4. 大模型返回响应，可能包含文本或工具调用请求
5. 如果有工具调用，客户端向MCP服务器发送工具调用请求
6. MCP服务器执行工具并通过SSE返回结果
7. 大模型拿到工具执行结果，继续生成内容
8. 整个过程通过SSE实时推送给客户端

### 2、传统通信流程

1. 客户端通过TCP连接到MCP服务器
2. 客户端发送请求消息
3. 服务器处理请求并返回响应
4. 如果响应包含工具调用，客户端通过MCP协议调用工具
5. 工具调用结果被添加到对话历史
6. 客户端继续与服务器交互直到获得最终响应
