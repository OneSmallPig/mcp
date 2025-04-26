package org.yubang.util.mcpdemo.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.yubang.util.mcpdemo.model.ExcelConfig;
import org.yubang.util.mcpdemo.service.ExcelExportService;

/**
 * Excel导出工具
 * 支持将数据导出为Excel格式，可以直接提供数据或通过SQL查询获取数据
 */
@Component
public class ExcelExportTool {

    private final ExcelExportService excelExportService;
    private final ObjectMapper objectMapper;

    public ExcelExportTool(ExcelExportService excelExportService) {
        this.excelExportService = excelExportService;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 将数据导出为Excel表格
     *
     * @param configJson Excel导出配置信息，JSON格式包含：
     *                  - data: 要导出的数据，格式为List<Map<String, Object>>
     *                  - fileName: 导出的Excel文件名，默认为"export"
     *                  - sheetName: 工作表名称，默认为"Sheet1"
     *                  - headerMapping: 列头映射，key为数据字段名，value为Excel列标题
     *                  - returnBase64: 是否返回Base64编码的Excel内容，默认为true
     *                  - sql: 自定义SQL语句，直接从数据库中查询数据
     *                  - databaseConfig: 数据库配置，用于SQL查询
     * @return Base64编码的Excel内容或包含文件名和内容的JSON
     */
    public String exportToExcel(String configJson) {
        try {
            // 解析配置JSON
            ExcelConfig config = objectMapper.readValue(configJson, ExcelConfig.class);
            
            // 导出Excel
            return excelExportService.exportToExcel(config);
        } catch (Exception e) {
            return "{\"error\": \"" + e.getMessage().replace("\"", "\\\"") + "\"}";
        }
    }
} 