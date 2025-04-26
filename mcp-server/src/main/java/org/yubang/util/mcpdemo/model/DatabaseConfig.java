package org.yubang.util.mcpdemo.model;

import lombok.Data;

/**
 * 数据库连接配置类
 */
@Data
public class DatabaseConfig {
    /**
     * JDBC驱动类名
     */
    private String driverClassName = "com.mysql.cj.jdbc.Driver";
    
    /**
     * 数据库连接URL
     */
    private String url;
    
    /**
     * 数据库用户名
     */
    private String username;
    
    /**
     * 数据库密码
     */
    private String password;
    
    /**
     * 要查询的表名
     */
    private String tableName;
    
    /**
     * SQL语句，如果为空则使用"SELECT * FROM tableName"
     */
    private String sql;
} 