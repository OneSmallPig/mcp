package org.yubang.util.mcpdemo.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.yubang.util.mcpdemo.model.ApiConfig;
import org.yubang.util.mcpdemo.service.ApiService;

/**
 * API调用工具
 * 通过配置的API信息，访问对应接口，返回接口响应数据
 */
@Component
public class ApiCallTool {

    private final ApiService apiService;
    private final ObjectMapper objectMapper;

    public ApiCallTool(ApiService apiService) {
        this.apiService = apiService;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 调用API接口
     *
     * @param configJson API配置信息，JSON格式包含：
     *                  - url: 请求URL(必填)
     *                  - method: 请求方法(GET/POST/PUT/DELETE等)，默认为GET
     *                  - headers: 请求头，格式为 {"headerName": "headerValue", ...}
     *                  - body: 请求体，用于POST/PUT等请求
     *                  - params: URL参数，格式为 {"paramName": "paramValue", ...}
     *                  - connectTimeout: 连接超时时间(毫秒)，默认5000
     *                  - readTimeout: 读取超时时间(毫秒)，默认5000
     * @return API响应结果
     */
    public String callApi(String configJson) {
        try {
            // 解析配置JSON
            ApiConfig config = objectMapper.readValue(configJson, ApiConfig.class);
            
            // 调用API
            return apiService.callApi(config);
        } catch (Exception e) {
            return "{\"error\": \"" + e.getMessage().replace("\"", "\\\"") + "\"}";
        }
    }
} 