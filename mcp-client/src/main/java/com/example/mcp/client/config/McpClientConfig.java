package com.example.mcp.client.config;

import com.example.mcp.client.model.Tool;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileReader;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.ToDoubleBiFunction;

/**
 * MCP客户端配置类
 */
public class McpClientConfig {
    private static final Logger log = LoggerFactory.getLogger(McpClientConfig.class);
    
    private String apiKey;
    private String endpoint;
    private String model;
    private int timeout;
    private int retries;
    private String serverUrl;
    private String serverPath;
    private String serverCommand;
    private String[] serverArgs;
    private boolean useLocalServer;
    private boolean useMcpProtocol; // 是否使用MCP协议
    
    // 新增SSE相关配置
    private String sseServerUrl;
    private String sseServerPath;
    private boolean useSse = false;
    
    private List<Tool> tools = new ArrayList<>();
    
    private static McpClientConfig instance;
    
    private McpClientConfig() {
        loadConfig();
    }
    
    public static synchronized McpClientConfig getInstance() {
        if (instance == null) {
            instance = new McpClientConfig();
        }
        return instance;
    }
    
    @SuppressWarnings("unchecked")
    private void loadConfig() {
        try {
            // 首先加载内部配置文件
            loadInternalConfig();
            
            // 然后尝试加载外部配置文件，如果存在则会覆盖内部配置
            loadExternalConfig();
            
            // 最后从环境变量加载配置，优先级最高
            overrideWithEnvVars();
            
            log.info("MCP Client配置加载完成");
        } catch (Exception e) {
            log.error("加载配置文件失败", e);
        }
    }
    
    @SuppressWarnings("unchecked")
    private void loadInternalConfig() {
        try {
            InputStream inputStream = getClass().getClassLoader().getResourceAsStream("application.yml");
            if (inputStream == null) {
                log.error("配置文件 application.yml 未找到！");
                return;
            }
            
            Yaml yaml = new Yaml();
            Map<String, Object> config = yaml.load(inputStream);
            
            Map<String, Object> mcp = (Map<String, Object>) config.get("mcp");
            if (mcp == null) {
                log.error("配置文件中未找到 mcp 配置项！");
                return;
            }
            
            Map<String, Object> client = (Map<String, Object>) mcp.get("client");
            if (client == null) {
                log.error("配置文件中未找到 mcp.client 配置项！");
                return;
            }
            
            Map<String, Object> volcano = (Map<String, Object>) client.get("volcano");
            if (volcano != null) {
                this.apiKey = (String) volcano.get("apiKey");
                
                Map<String, Object> deepseek = (Map<String, Object>) volcano.get("deepseek");
                if (deepseek != null) {
                    this.endpoint = (String) deepseek.get("endpoint");
                    this.model = (String) deepseek.get("model");
                }
                
                Map<String, Object> request = (Map<String, Object>) volcano.get("request");
                if (request != null) {
                    this.timeout = ((Number) request.get("timeout")).intValue();
                    this.retries = ((Number) request.get("retries")).intValue();
                }
            }
            
            Map<String, Object> server = (Map<String, Object>) client.get("server");
            if (server != null) {
                this.serverUrl = (String) server.get("url");
                this.serverPath = (String) server.get("path");
                
                // 检查是否配置了使用MCP协议
                if (server.containsKey("useMcpProtocol")) {
                    this.useMcpProtocol = Boolean.TRUE.equals(server.get("useMcpProtocol"));
                    log.info("内部配置设置MCP协议: {}", this.useMcpProtocol);
                } else {
                    // 默认情况下使用HTTP REST API
                    this.useMcpProtocol = false;
                }
            }
            
            log.info("内部配置文件加载完成");
        } catch (Exception e) {
            log.error("加载内部配置文件失败", e);
        }
    }
    
    private void loadExternalConfig() {
        // 尝试加载几个可能的外部配置文件位置
        String[] possibleLocations = {
            "./mcp.json",  // 当前目录
            System.getProperty("user.home") + "/.mcp/mcp.json",  // 用户目录
            System.getProperty("user.home") + "/.cursor/mcp.json"  // Cursor应用目录
        };
        
        for (String location : possibleLocations) {
            File configFile = new File(location);
            if (configFile.exists() && configFile.isFile()) {
                try {
                    log.info("发现外部配置文件: {}", location);
                    String content = new String(Files.readAllBytes(Paths.get(location)));
                    Gson gson = new Gson();
                    JsonObject config = gson.fromJson(content, JsonObject.class);
                    
                    // 检查是否有MCP服务器配置
                    if (config.has("McpDemo") || config.has("McpServer")) {
                        JsonObject serverConfig = config.has("McpDemo") ? 
                                config.getAsJsonObject("McpDemo") : 
                                config.getAsJsonObject("McpServer");
                        
                        // 首先直接检查顶层配置
                        if (serverConfig.has("command")) {
                            this.serverCommand = serverConfig.get("command").getAsString();
                            this.useLocalServer = true;
                            
                            if (serverConfig.has("args") && serverConfig.get("args").isJsonArray()) {
                                this.serverArgs = gson.fromJson(serverConfig.get("args"), String[].class);
                            }
                            
                            log.info("加载到本地MCP服务器配置，命令: {}", serverCommand);
                        } 
                        // 检查是否有子配置项
                        else {
                            // 尝试从子属性中加载配置
                            for (String key : serverConfig.keySet()) {
                                if (serverConfig.get(key).isJsonObject()) {
                                    JsonObject subConfig = serverConfig.getAsJsonObject(key);
                                    if (subConfig.has("command")) {
                                        this.serverCommand = subConfig.get("command").getAsString();
                                        this.useLocalServer = true;
                                        
                                        if (subConfig.has("args") && subConfig.get("args").isJsonArray()) {
                                            this.serverArgs = gson.fromJson(subConfig.get("args"), String[].class);
                                        }
                                        
                                        log.info("从子配置 '{}' 加载到本地MCP服务器配置，命令: {}", key, serverCommand);
                                        break;  // 找到一个有效配置后停止查找
                                    }
                                }
                            }
                        }
                    }
                    
                    // 检查是否有API密钥配置
                    if (config.has("apiKey")) {
                        this.apiKey = config.get("apiKey").getAsString();
                        log.info("从外部配置加载API密钥");
                    }
                    
                    // 检查是否有服务器URL配置
                    if (config.has("server") && config.getAsJsonObject("server").has("url")) {
                        this.serverUrl = config.getAsJsonObject("server").get("url").getAsString();
                        
                        if (config.getAsJsonObject("server").has("path")) {
                            this.serverPath = config.getAsJsonObject("server").get("path").getAsString();
                        }
                        
                        // 检查是否配置了使用MCP协议
                        JsonObject serverObj = config.getAsJsonObject("server");
                        if (serverObj.has("useMcpProtocol")) {
                            this.useMcpProtocol = serverObj.get("useMcpProtocol").getAsBoolean();
                            log.info("从外部配置设置MCP协议: {}", this.useMcpProtocol);
                        }
                        
                        log.info("从外部配置加载服务器URL: {}", serverUrl);
                    }
                    
                    // 检查是否有SSE配置
                    if (config.has("sse")) {
                        JsonObject sseObj = config.getAsJsonObject("sse");
                        if (sseObj.has("serverUrl")) {
                            this.sseServerUrl = sseObj.get("serverUrl").getAsString();
                            log.info("从外部配置加载SSE服务器URL: {}", this.sseServerUrl);
                        }
                        if (sseObj.has("serverPath")) {
                            this.sseServerPath = sseObj.get("serverPath").getAsString();
                            log.info("从外部配置加载SSE服务器路径: {}", this.sseServerPath);
                        }
                        if (sseObj.has("useSse")) {
                            this.useSse = sseObj.get("useSse").getAsBoolean();
                            log.info("从外部配置加载SSE使用设置: {}", this.useSse);
                        }
                    }
                    
                    // 加载工具配置
                    if (config.has("tools") && config.get("tools").isJsonArray()) {
                        JsonArray toolsArray = config.getAsJsonArray("tools");
                        Type toolListType = new TypeToken<List<Tool>>(){}.getType();
                        List<Tool> loadedTools = gson.fromJson(toolsArray, toolListType);
                        if (loadedTools != null && !loadedTools.isEmpty()) {
                            this.tools.addAll(loadedTools);
                            log.info("从外部配置加载了 {} 个工具", loadedTools.size());
                            for (Tool tool : loadedTools) {
                                log.info("工具: {} - {}", tool.getName(), tool.getDescription());
                            }
                        }
                    }
                    
                    // 找到一个有效的配置文件后不再继续查找
                    break;
                } catch (Exception e) {
                    log.error("加载外部配置文件 {} 失败: {}", location, e.getMessage());
                }
            }
        }
    }
    
    // Override configuration with environment variables if they exist
    public void overrideWithEnvVars() {
        String envApiKey = System.getenv("VOLCANO_API_KEY");
        if (envApiKey != null && !envApiKey.isEmpty()) {
            this.apiKey = envApiKey;
        }
        
        String envEndpoint = System.getenv("VOLCANO_ENDPOINT");
        if (envEndpoint != null && !envEndpoint.isEmpty()) {
            this.endpoint = envEndpoint;
        }
        
        String envModel = System.getenv("VOLCANO_MODEL");
        if (envModel != null && !envModel.isEmpty()) {
            this.model = envModel;
        }
        
        String envServerUrl = System.getenv("MCP_SERVER_URL");
        if (envServerUrl != null && !envServerUrl.isEmpty()) {
            this.serverUrl = envServerUrl;
        }
        
        String envServerPath = System.getenv("MCP_SERVER_PATH");
        if (envServerPath != null && !envServerPath.isEmpty()) {
            this.serverPath = envServerPath;
        }
        
        // 从环境变量加载是否使用MCP协议
        String envUseMcpProtocol = System.getenv("MCP_USE_PROTOCOL");
        if (envUseMcpProtocol != null && !envUseMcpProtocol.isEmpty()) {
            this.useMcpProtocol = Boolean.parseBoolean(envUseMcpProtocol);
            log.info("从环境变量设置MCP协议: {}", this.useMcpProtocol);
        }
        
        // 尝试从环境变量加载SSE相关配置
        String sseServerUrl = System.getenv("MCP_SSE_SERVER_URL");
        if (sseServerUrl != null && !sseServerUrl.isEmpty()) {
            this.sseServerUrl = sseServerUrl;
            this.useSse = true;
            log.info("从环境变量加载SSE服务器URL: {}", sseServerUrl);
        }
        
        String sseServerPath = System.getenv("MCP_SSE_SERVER_PATH");
        if (sseServerPath != null && !sseServerPath.isEmpty()) {
            this.sseServerPath = sseServerPath;
            log.info("从环境变量加载SSE服务器路径: {}", sseServerPath);
        }
        
        String useSse = System.getenv("MCP_USE_SSE");
        if (useSse != null && !useSse.isEmpty()) {
            this.useSse = Boolean.parseBoolean(useSse);
            log.info("从环境变量加载SSE使用设置: {}", this.useSse);
        }
    }

    /**
     * 清除现有工具列表
     */
    public void clearTools() {
        this.tools.clear();
        log.info("已清除工具列表");
    }

    /**
     * 添加工具到工具列表
     * 
     * @param newTools 要添加的工具列表
     */
    public void addTools(List<Tool> newTools) {
        if (newTools != null && !newTools.isEmpty()) {
            this.tools.addAll(newTools);
            log.info("已添加 {} 个工具到工具列表", newTools.size());
        }
    }

    /**
     * 如果是本地服务器但没有设置URL，则设置默认的本地服务器URL
     */
    public void setDefaultLocalServerUrl() {
        //todo 这里我需要进行配置判断，获取jar包的启动端口
        if (this.serverUrl == null || this.serverUrl.isEmpty()) {
            this.serverUrl = "http://localhost:9507";
            log.info("设置默认本地服务器URL: {}", this.serverUrl);
        }
        
        // 自动设置SSE服务器URL为同一本地服务器
        if (this.isUseLocalServer() && (this.sseServerUrl == null || this.sseServerUrl.isEmpty())) {
            this.sseServerUrl = this.serverUrl;
            this.sseServerPath = this.sseServerPath != null ? this.sseServerPath : "api/chat/stream";
            this.useSse = true;
            log.info("检测到本地MCP服务器，自动设置SSE服务器URL: {}", this.sseServerUrl);
            log.info("SSE服务器路径: {}", this.sseServerPath);
        }
    }

    /**
     * 设置服务器URL，支持HTTP和TCP
     * 
     * @param url 服务器URL
     */
    public void setServerUrl(String url) {
        this.serverUrl = url;
        log.info("设置服务器URL: {}", url);
    }

    /**
     * 获取MCP服务器的端口（如果未指定则返回默认端口）
     * 
     * @return MCP服务器端口
     */
    public int getMcpServerPort() {
        if (this.serverUrl == null || this.serverUrl.isEmpty()) {
            return 8080; // 默认端口
        }
        
        try {
            java.net.URL url = new java.net.URL(this.serverUrl);
            return url.getPort() != -1 ? url.getPort() : 
                   this.serverUrl.startsWith("https") ? 443 : 80;
        } catch (Exception e) {
            log.warn("解析服务器URL端口失败: {}", e.getMessage());
            return 8080; // 出错时返回默认端口
        }
    }

    /**
     * 判断服务器URL是否为本地回环地址
     * 
     * @return 是否为本地地址
     */
    public boolean isLocalServerUrl() {
        if (this.serverUrl == null || this.serverUrl.isEmpty()) {
            return false;
        }
        
        try {
            java.net.URL url = new java.net.URL(this.serverUrl);
            String host = url.getHost();
            return "localhost".equalsIgnoreCase(host) || 
                   "127.0.0.1".equals(host) || 
                   "::1".equals(host);
        } catch (Exception e) {
            return false;
        }
    }

    public String getApiKey() {
        return apiKey;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public String getModel() {
        return model;
    }

    public int getTimeout() {
        return timeout;
    }

    public int getRetries() {
        return retries;
    }

    public String getServerUrl() {
        return serverUrl;
    }

    public String getServerPath() {
        return serverPath;
    }
    
    public String getServerCommand() {
        return serverCommand;
    }
    
    public String[] getServerArgs() {
        return serverArgs;
    }
    
    public boolean isUseLocalServer() {
        return useLocalServer;
    }
    
    public List<Tool> getTools() {
        return tools;
    }

    public boolean isUseMcpProtocol() {
        return useMcpProtocol;
    }
    
    public void setUseMcpProtocol(boolean useMcpProtocol) {
        this.useMcpProtocol = useMcpProtocol;
    }

    // 新增SSE相关配置的getter和setter
    public String getSseServerUrl() {
        // 如果未配置SSE服务器URL，则使用普通服务器URL
        return sseServerUrl != null ? sseServerUrl : serverUrl;
    }
    
    public void setSseServerUrl(String sseServerUrl) {
        this.sseServerUrl = sseServerUrl;
    }
    
    public String getSseServerPath() {
        // 如果未配置SSE服务器路径，则使用默认路径
        return sseServerPath != null ? sseServerPath : "api/chat/stream";
    }
    
    public void setSseServerPath(String sseServerPath) {
        this.sseServerPath = sseServerPath;
    }
    
    public boolean isUseSse() {
        return useSse;
    }
    
    public void setUseSse(boolean useSse) {
        this.useSse = useSse;
    }
} 