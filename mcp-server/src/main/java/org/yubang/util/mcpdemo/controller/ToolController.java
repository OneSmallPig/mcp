package org.yubang.util.mcpdemo.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.yubang.util.mcpdemo.model.ToolInfo;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * 工具控制器
 * 提供获取所有可用工具的API接口
 */
@RestController
@RequestMapping("/api/tools")
public class ToolController {

    @Autowired
    private List<Object> toolServices;  // 自动注入所有标记了@Tool的服务

    /**
     * 获取所有可用工具列表
     * @return 工具信息列表
     */
    @GetMapping
    public List<ToolInfo> getAllTools() {
        List<ToolInfo> toolInfoList = new ArrayList<>();
        
        for (Object service : toolServices) {
            Class<?> serviceClass = service.getClass();
            for (Method method : serviceClass.getMethods()) {
                // 检查方法是否有@Tool注解
                if (method.isAnnotationPresent(org.springframework.ai.tool.annotation.Tool.class)) {
                    org.springframework.ai.tool.annotation.Tool annotation = 
                        method.getAnnotation(org.springframework.ai.tool.annotation.Tool.class);
                    
                    ToolInfo toolInfo = new ToolInfo();
                    toolInfo.setName(annotation.name());
                    toolInfo.setDescription(annotation.description());
                    toolInfo.setMethodName(method.getName());
                    toolInfo.setClassName(serviceClass.getSimpleName());
                    toolInfoList.add(toolInfo);
                }
            }
        }
        
        return toolInfoList;
    }
} 