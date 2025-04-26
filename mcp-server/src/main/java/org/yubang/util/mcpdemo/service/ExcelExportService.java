package org.yubang.util.mcpdemo.service;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Service;
import org.yubang.util.mcpdemo.model.ExcelConfig;

import java.io.ByteArrayOutputStream;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Excel导出服务类
 */
@Service
public class ExcelExportService {

    private final DatabaseService databaseService;

    public ExcelExportService(DatabaseService databaseService) {
        this.databaseService = databaseService;
    }

    /**
     * 将数据导出为Excel
     *
     * @param config Excel导出配置
     * @return Base64编码的Excel内容或JSON格式错误信息
     */
    @Tool(name = "导出Excel表格", description = "将数据导出为Excel表格格式，支持直接提供数据或从数据库查询")
    public String exportToExcel(ExcelConfig config) {
        try {
            // 获取数据
            List<Map<String, Object>> data = config.getData();
            
            // 如果提供了SQL，则从数据库获取数据
            if (config.getSql() != null && !config.getSql().isEmpty() && config.getDatabaseConfig() != null) {
                // 设置SQL到数据库配置
                config.getDatabaseConfig().setSql(config.getSql());
                // 查询数据库
                data = databaseService.queryTable(config.getDatabaseConfig());
            }
            
            // 如果数据为空，则返回错误信息
            if (data == null || data.isEmpty()) {
                return "{\"error\": \"No data available for export\"}";
            }
            
            // 创建Excel工作簿
            try (Workbook workbook = new XSSFWorkbook()) {
                // 创建工作表
                Sheet sheet = workbook.createSheet(config.getSheetName());
                
                // 获取所有列名
                Map<String, String> headers = determineHeaders(data, config.getHeaderMapping());
                
                // 创建表头行
                Row headerRow = sheet.createRow(0);
                int colIndex = 0;
                
                // 存储列名与索引映射
                Map<String, Integer> columnIndexes = new LinkedHashMap<>();
                
                // 填充表头
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    String columnName = entry.getKey();
                    String headerName = entry.getValue();
                    
                    Cell cell = headerRow.createCell(colIndex);
                    cell.setCellValue(headerName);
                    
                    columnIndexes.put(columnName, colIndex);
                    colIndex++;
                }
                
                // 填充数据行
                int rowIndex = 1;
                for (Map<String, Object> rowData : data) {
                    Row row = sheet.createRow(rowIndex++);
                    
                    for (Map.Entry<String, Integer> entry : columnIndexes.entrySet()) {
                        String columnName = entry.getKey();
                        int columnIndex = entry.getValue();
                        
                        Object value = rowData.get(columnName);
                        Cell cell = row.createCell(columnIndex);
                        
                        if (value != null) {
                            setCellValue(cell, value);
                        }
                    }
                }
                
                // 自动调整列宽
                for (int i = 0; i < headers.size(); i++) {
                    sheet.autoSizeColumn(i);
                }
                
                // 将工作簿写入字节数组
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                workbook.write(baos);
                byte[] bytes = baos.toByteArray();
                
                // 返回Base64编码的Excel内容
                String base64Content = Base64.getEncoder().encodeToString(bytes);
                
                if (config.isReturnBase64()) {
                    return base64Content;
                } else {
                    // 返回包含文件名和Base64的JSON
                    return "{\"fileName\": \"" + config.getFileName() + ".xlsx\", \"content\": \"" + base64Content + "\"}";
                }
            }
        } catch (Exception e) {
            return "{\"error\": \"" + e.getMessage().replace("\"", "\\\"") + "\"}";
        }
    }
    
    /**
     * 根据数据和映射确定表头
     */
    private Map<String, String> determineHeaders(List<Map<String, Object>> data, Map<String, String> headerMapping) {
        Map<String, String> headers = new LinkedHashMap<>();
        
        // 获取第一行数据的所有字段作为默认列名
        if (!data.isEmpty()) {
            for (String key : data.get(0).keySet()) {
                // 如果有映射，则使用映射的标题，否则使用字段名本身
                String headerName = (headerMapping != null && headerMapping.containsKey(key)) ? 
                                    headerMapping.get(key) : key;
                headers.put(key, headerName);
            }
        }
        
        return headers;
    }
    
    /**
     * 根据值类型设置单元格值
     */
    private void setCellValue(Cell cell, Object value) {
        if (value instanceof String) {
            cell.setCellValue((String) value);
        } else if (value instanceof Number) {
            if (value instanceof Integer || value instanceof Long) {
                cell.setCellValue(((Number) value).longValue());
            } else {
                cell.setCellValue(((Number) value).doubleValue());
            }
        } else if (value instanceof Boolean) {
            cell.setCellValue((Boolean) value);
        } else if (value instanceof java.util.Date) {
            cell.setCellValue((java.util.Date) value);
        } else {
            cell.setCellValue(value.toString());
        }
    }
} 