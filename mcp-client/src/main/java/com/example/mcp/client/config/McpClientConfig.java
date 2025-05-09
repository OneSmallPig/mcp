package com.example.mcp.client.config;

import cn.hutool.core.io.resource.ResourceUtil;
import com.example.mcp.client.model.Tool;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

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
    private String serverType; // 服务器类型："jar"或"http"
    private double temperature = 0.7; // 默认温度值
    
    // 新增SSE相关配置
    private String sseServerUrl;
    private String sseServerPath;
    private boolean useSse = false;
    
    // 自定义配置文件路径
    private static String customConfigPath;
    
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
    
    /**
     * 设置自定义配置文件路径
     * @param configPath 配置文件的完整路径
     * @return true如果文件存在且有效，否则返回false
     */
    public static boolean setCustomConfigPath(String configPath) {
        File file = new File(configPath);
        if (file.exists() && file.isFile()) {
            customConfigPath = configPath;
            
            // 如果已经创建了实例，则重新加载配置
            if (instance != null) {
                instance.loadConfig();
            }
            
            return true;
        } else {
            log.error("指定的配置文件不存在或不是有效文件: {}", configPath);
            return false;
        }
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
        // 如果设置了自定义配置文件路径，优先使用
        if (customConfigPath != null && !customConfigPath.isEmpty()) {
            loadConfigFromFile(new File(customConfigPath));
            return; // 加载自定义配置后不再尝试其他位置
        }
        
        // 尝试加载外部配置文件位置
        String[] possibleLocations = {
//                ResourceUtil.getResource("").getPath() + "/mcp.json",  // 当前目录
                System.getProperty("user.dir") + "/mcp.json",  // 运行时目录
                getJarDirectory() + "/mcp.json"  // Jar所在目录
        };
        
        for (String location : possibleLocations) {
            File configFile = new File(location);
            if (configFile.exists() && configFile.isFile()) {
                loadConfigFromFile(configFile);
                break; // 找到一个有效的配置文件后不再继续查找
            }
        }
    }
    
    /**
     * 从文件加载配置
     * @param configFile 配置文件
     */
    private void loadConfigFromFile(File configFile) {
        try {
            log.info("加载配置文件: {}", configFile.getAbsolutePath());
            String content = new String(Files.readAllBytes(Paths.get(configFile.getAbsolutePath())));
            Gson gson = new Gson();
            JsonObject config = gson.fromJson(content, JsonObject.class);
            
            // 检查是否有MCP服务器配置
            if (config.has("mcp-servers")) {
                JsonObject serverConfig = config.getAsJsonObject("mcp-servers");

                //判断mcp服务名称
                for (String key : serverConfig.keySet()) {
                    if (serverConfig.get(key).isJsonObject()) {
                        JsonObject subConfig = serverConfig.getAsJsonObject(key);
                        if (subConfig.has("command")) {
                            this.serverCommand = subConfig.get("command").getAsString();
                            this.useLocalServer = true;

                            if (subConfig.has("args") && subConfig.get("args").isJsonArray()) {
                                this.serverArgs = gson.fromJson(subConfig.get("args"), String[].class);
                            }

                            // 判断是否为jar类型的服务器
                            if (this.serverCommand.equals("java")) {
                                this.serverType = "jar";
                                log.info("检测到jar类型的MCP服务器");
                            } else {
                                this.serverType = "http";
                            }

                            log.info("加载到本地MCP服务器配置，命令: {}, 类型: {}", serverCommand, this.serverType);
                        }
                    }
                }
            }
            
            // 检查是否有API密钥配置
            if (config.has("apiKey")) {
                this.apiKey = config.get("apiKey").getAsString();
                log.info("从配置文件加载API密钥");
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
                    log.info("从配置文件设置MCP协议: {}", this.useMcpProtocol);
                }
                
                // 如果没有设置服务器类型，默认为http
                if (this.serverType == null) {
                    this.serverType = "http";
                }
                
                log.info("从配置文件加载服务器URL: {}", serverUrl);
            }
            
            // 检查是否有SSE配置
            if (config.has("sse")) {
                JsonObject sseObj = config.getAsJsonObject("sse");
                if (sseObj.has("serverUrl")) {
                    this.sseServerUrl = sseObj.get("serverUrl").getAsString();
                    log.info("从配置文件加载SSE服务器URL: {}", this.sseServerUrl);
                }
                if (sseObj.has("serverPath")) {
                    this.sseServerPath = sseObj.get("serverPath").getAsString();
                    log.info("从配置文件加载SSE服务器路径: {}", this.sseServerPath);
                }
                if (sseObj.has("useSse")) {
                    this.useSse = sseObj.get("useSse").getAsBoolean();
                    log.info("从配置文件加载SSE使用设置: {}", this.useSse);
                }
            }
            
            // 加载工具配置
            if (config.has("tools") && config.get("tools").isJsonArray()) {
                JsonArray toolsArray = config.getAsJsonArray("tools");
                Type toolListType = new TypeToken<List<Tool>>(){}.getType();
                List<Tool> loadedTools = gson.fromJson(toolsArray, toolListType);
                if (loadedTools != null && !loadedTools.isEmpty()) {
                    this.tools.addAll(loadedTools);
                    log.info("从配置文件加载了 {} 个工具", loadedTools.size());
                    for (Tool tool : loadedTools) {
                        log.info("工具: {} - {}", tool.getName(), tool.getDescription());
                    }
                }
            }
        } catch (Exception e) {
            log.error("加载配置文件失败: " + configFile.getAbsolutePath(), e);
        }
    }
    
    // Override configuration with environment variables if they exist
    public void overrideWithEnvVars() {
        // 读取环境变量
        String apiKey = System.getenv("MCP_API_KEY");
        if (apiKey != null && !apiKey.isEmpty()) {
            this.apiKey = apiKey;
        }
        
        String endpoint = System.getenv("MCP_ENDPOINT");
        if (endpoint != null && !endpoint.isEmpty()) {
            this.endpoint = endpoint;
        }
        
        String model = System.getenv("MCP_MODEL");
        if (model != null && !model.isEmpty()) {
            this.model = model;
        }
        
        String serverUrl = System.getenv("MCP_SERVER_URL");
        if (serverUrl != null && !serverUrl.isEmpty()) {
            this.serverUrl = serverUrl;
        }
        
        String serverPath = System.getenv("MCP_SERVER_PATH");
        if (serverPath != null && !serverPath.isEmpty()) {
            this.serverPath = serverPath;
        }
        
        String useMcpProtocolStr = System.getenv("MCP_USE_PROTOCOL");
        if (useMcpProtocolStr != null && !useMcpProtocolStr.isEmpty()) {
            this.useMcpProtocol = Boolean.parseBoolean(useMcpProtocolStr);
        }
        
        // SSE环境变量
        String sseServerUrl = System.getenv("MCP_SSE_SERVER_URL");
        if (sseServerUrl != null && !sseServerUrl.isEmpty()) {
            this.sseServerUrl = sseServerUrl;
            this.useSse = true;
        }
        
        String sseServerPath = System.getenv("MCP_SSE_SERVER_PATH");
        if (sseServerPath != null && !sseServerPath.isEmpty()) {
            this.sseServerPath = sseServerPath;
        }
        
        String useSseStr = System.getenv("MCP_USE_SSE");
        if (useSseStr != null && !useSseStr.isEmpty()) {
            this.useSse = Boolean.parseBoolean(useSseStr);
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
        if (this.serverUrl == null || this.serverUrl.isEmpty()) {
            this.serverUrl = "http://localhost:8080";
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
        // 如果没有专门设置SSE服务器URL，则使用通用服务器URL
        return sseServerUrl != null && !sseServerUrl.isEmpty() ? sseServerUrl : serverUrl;
    }
    
    public void setSseServerUrl(String sseServerUrl) {
        this.sseServerUrl = sseServerUrl;
    }
    
    public String getSseServerPath() {
        // 如果没有专门设置SSE服务器路径，使用默认值"sse"
        return sseServerPath != null && !sseServerPath.isEmpty() ? sseServerPath : "sse";
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

    public String getServerType() {
        return serverType;
    }
    
    public double getTemperature() {
        return temperature;
    }
    
    public void setTemperature(double temperature) {
        this.temperature = temperature;
    }

    private String getJarDirectory() {
        try {
            String path = McpClientConfig.class.getProtectionDomain().getCodeSource().getLocation().getPath();
            // 处理URL编码的路径
            path = java.net.URLDecoder.decode(path, "UTF-8");
            // 如果是jar文件，获取其所在目录
            if (path.endsWith(".jar")) {
                path = path.substring(0, path.lastIndexOf("/"));
            }
            log.info("获取到Jar文件所在目录: {}", path);
            return path;
        } catch (Exception e) {
            log.error("获取Jar文件所在目录失败", e);
            return System.getProperty("user.dir"); // 默认返回当前工作目录
        }
    }
} 