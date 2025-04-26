package org.yubang.util.mcpdemo.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.yubang.util.mcpdemo.service.ApiService;
import org.yubang.util.mcpdemo.service.DatabaseService;

import java.util.Arrays;
import java.util.List;

/**
 * 工具配置类
 * 用于配置和收集所有工具服务
 */
@Configuration
public class ToolConfiguration {
    
    /**
     * 收集所有工具服务
     * @param apiService API服务
     * @param databaseService 数据库服务
     * @return 工具服务列表
     */
    @Bean
    public List<Object> toolServices(ApiService apiService, DatabaseService databaseService) {
        return Arrays.asList(apiService, databaseService);
    }
} 