package org.yubang.util.mcpdemo.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.yubang.util.mcpdemo.model.DatabaseConfig;
import org.yubang.util.mcpdemo.service.DatabaseService;

import java.util.List;
import java.util.Map;

/**
 * 数据库查询工具
 * 通过配置的数据库连接信息，访问对应的数据表，返回数据
 */
@Component
public class DatabaseQueryTool {

    private final DatabaseService databaseService;
    private final ObjectMapper objectMapper;

    public DatabaseQueryTool(DatabaseService databaseService) {
        this.databaseService = databaseService;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 查询数据库表数据
     *
     * @param configJson 数据库配置信息，JSON格式包含：
     *                  - driverClassName: JDBC驱动类名，默认为MySQL
     *                  - url: 数据库连接URL
     *                  - username: 数据库用户名
     *                  - password: 数据库密码
     *                  - tableName: 要查询的表名
     *                  - sql: 自定义SQL语句，可选，如不提供则使用"SELECT * FROM tableName"
     * @return 查询结果的JSON字符串
     */
    public String queryDatabase(String configJson) {
        try {
            // 解析配置JSON
            DatabaseConfig config = objectMapper.readValue(configJson, DatabaseConfig.class);
            
            // 执行数据库查询
            List<Map<String, Object>> results = databaseService.queryTable(config);
            
            // 转换结果为JSON
            return objectMapper.writeValueAsString(results);
        } catch (Exception e) {
            return "{\"error\": \"" + e.getMessage().replace("\"", "\\\"") + "\"}";
        }
    }
}