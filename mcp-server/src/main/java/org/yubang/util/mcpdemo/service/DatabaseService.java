package org.yubang.util.mcpdemo.service;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.ai.tool.annotation.Tool;
import org.yubang.util.mcpdemo.model.DatabaseConfig;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据库服务类
 */
@Service
public class DatabaseService {

    /**
     * 根据配置信息查询数据库表数据
     *
     * @param config 数据库配置信息
     * @return 查询结果列表
     * @throws Exception 如果查询过程中发生错误
     */
    @Tool(name = "数据库对接", description = "通过配置的数据库连接信息，访问对应的数据表，返回数据表的所有数据")
    public List<Map<String, Object>> queryTable(DatabaseConfig config) throws Exception {
        // 创建Hikari连接池配置
        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setDriverClassName(config.getDriverClassName());
        hikariConfig.setJdbcUrl(config.getUrl());
        hikariConfig.setUsername(config.getUsername());
        hikariConfig.setPassword(config.getPassword());
        hikariConfig.setMaximumPoolSize(1);
        hikariConfig.setMinimumIdle(1);
        hikariConfig.setIdleTimeout(10000);
        hikariConfig.setMaxLifetime(30000);
        hikariConfig.setConnectionTimeout(5000);
        
        List<Map<String, Object>> resultList = new ArrayList<>();
        
        try (HikariDataSource dataSource = new HikariDataSource(hikariConfig);
             Connection connection = dataSource.getConnection()) {
             
            String sql = config.getSql();
            // 如果SQL为空，则使用默认查询语句
            if (sql == null || sql.trim().isEmpty()) {
                sql = "SELECT * FROM " + config.getTableName();
            }
            
            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery(sql)) {
                 
                ResultSetMetaData metaData = rs.getMetaData();
                int columnCount = metaData.getColumnCount();
                
                // 遍历结果集
                while (rs.next()) {
                    Map<String, Object> row = new HashMap<>();
                    
                    // 遍历每列数据
                    for (int i = 1; i <= columnCount; i++) {
                        String columnName = metaData.getColumnName(i);
                        Object value = rs.getObject(i);
                        row.put(columnName, value);
                    }
                    
                    resultList.add(row);
                }
            }
        }
        
        return resultList;
    }
} 