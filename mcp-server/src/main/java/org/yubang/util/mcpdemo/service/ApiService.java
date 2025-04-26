package org.yubang.util.mcpdemo.service;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.BodyInserters;
import org.yubang.util.mcpdemo.model.ApiConfig;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;

/**
 * API服务类
 */
@Service
public class ApiService {

    /**
     * 执行API请求
     *
     * @param config API配置信息
     * @return API响应结果
     */
    @Tool(name = "API对接", description = "通过配置的API信息，访问对应接口，获取接口返回数据")
    public String callApi(ApiConfig config) {
        // 创建WebClient实例
        WebClient.Builder webClientBuilder = WebClient.builder()
                .baseUrl(config.getUrl());
        
        // 构建WebClient
        WebClient webClient = webClientBuilder.build();
        
        // 根据请求方法执行不同类型的请求
        String result;
        
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
            case "PUT":
                requestSpec = webClient.put()
                        .uri(uriBuilder -> {
                            config.getParams().forEach(uriBuilder::queryParam);
                            return uriBuilder.build();
                        })
                        .body(BodyInserters.fromValue(config.getBody()));
                break;
            case "DELETE":
                requestSpec = webClient.delete()
                        .uri(uriBuilder -> {
                            config.getParams().forEach(uriBuilder::queryParam);
                            return uriBuilder.build();
                        });
                break;
            case "GET":
            default:
                requestSpec = webClient.get()
                        .uri(uriBuilder -> {
                            config.getParams().forEach(uriBuilder::queryParam);
                            return uriBuilder.build();
                        });
                break;
        }
        
        // 添加请求头
        for (Map.Entry<String, String> header : config.getHeaders().entrySet()) {
            requestSpec = requestSpec.header(header.getKey(), header.getValue());
        }
        
        // 执行请求并获取响应
        result = requestSpec.retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofMillis(config.getReadTimeout()))
                .block(Duration.ofMillis(config.getConnectTimeout()));
        
        return result;
    }
} 