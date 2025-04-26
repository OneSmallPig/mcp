package org.yubang.util.mcpdemo.model;

import lombok.Data;
import java.util.List;
import java.util.Map;

/**
 * Excel导出配置类
 */
@Data
public class ExcelConfig {
    /**
     * 数据源，可以是List<Map<String, Object>>格式的数据
     */
    private List<Map<String, Object>> data;
    
    /**
     * Excel文件名，不包含扩展名
     */
    private String fileName = "export";
    
    /**
     * 工作表名称
     */
    private String sheetName = "Sheet1";
    
    /**
     * 列头映射，key为数据字段名，value为Excel列标题
     * 如果为空，则使用数据字段名作为列标题
     */
    private Map<String, String> headerMapping;
    
    /**
     * 是否返回Base64编码的Excel内容
     * true - 返回Base64字符串
     * false - 返回二进制数据的Base64
     */
    private boolean returnBase64 = true;
    
    /**
     * 自定义SQL语句，可以直接从数据库中查询数据
     * 如果提供了此属性，将忽略data字段
     */
    private String sql;
    
    /**
     * 数据库配置，用于SQL查询
     * 只有当sql不为空时才需要
     */
    private DatabaseConfig databaseConfig;
} 