package org.yubang.util.mcpdemo.model;

import lombok.Data;
import java.util.Map;
import java.util.HashMap;

/**
 * API接口调用配置类
 */
@Data
public class ApiConfig {
    /**
     * 请求URL
     */
    private String url;
    
    /**
     * 请求方法: GET, POST, PUT, DELETE等
     */
    private String method = "GET";
    
    /**
     * 请求头
     */
    private Map<String, String> headers = new HashMap<>();
    
    /**
     * 请求体，用于POST/PUT等请求
     */
    private String body;
    
    /**
     * 请求参数，用于拼接在URL后的查询参数
     */
    private Map<String, String> params = new HashMap<>();
    
    /**
     * 连接超时时间(毫秒)
     */
    private int connectTimeout = 5000;
    
    /**
     * 读取超时时间(毫秒)
     */
    private int readTimeout = 5000;
} 