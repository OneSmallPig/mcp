package org.yubang.util.mcpdemo;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.ToolCallbacks;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.yubang.util.mcpdemo.service.ApiService;
import org.yubang.util.mcpdemo.service.DatabaseService;
import org.yubang.util.mcpdemo.service.ExcelExportService;

import java.util.List;

@SpringBootApplication
@EnableWebMvc
public class McpDemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(McpDemoApplication.class, args);
    }

    @Bean
    public ToolCallbackProvider weatherTools(ApiService apiService, DatabaseService databaseService, ExcelExportService excelExportService) {
        return MethodToolCallbackProvider.builder().toolObjects(apiService, databaseService, excelExportService).build();
    }

}
