package org.yubang.util.mcpdemo.service;

import cn.hutool.json.JSONUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

/**
 * 工具执行服务
 * 负责根据工具名称和参数执行对应的工具
 */
@Service
public class ToolExecutionService {
    private static final Logger log = LoggerFactory.getLogger(ToolExecutionService.class);
    
    @Autowired
    private List<Object> toolServices; // 自动注入所有带有@Tool注解的服务
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    /**
     * 根据工具名称和参数执行工具
     * 
     * @param toolName 工具名称
     * @param arguments 工具参数
     * @return 工具执行结果
     */
    public String executeToolByName(String toolName, Map<String, Object> arguments) {
        log.info("执行工具: {}, 参数: {}", toolName, arguments);
        
        try {
            // 查找匹配的工具方法
            for (Object service : toolServices) {
                for (Method method : service.getClass().getMethods()) {
                    if (method.isAnnotationPresent(Tool.class)) {
                        Tool annotation = method.getAnnotation(Tool.class);
                        
                        // 检查工具名称是否匹配
                        if (annotation.name().equals(toolName)) {
                            log.info("找到匹配的工具: {}.{}", service.getClass().getSimpleName(), method.getName());
                            return executeToolMethod(service, method, arguments);
                        }
                    }
                }
            }
            
            // 如果没有找到匹配的工具
            log.warn("未找到匹配的工具: {}", toolName);
            return "未找到匹配的工具: " + toolName;
        } catch (Exception e) {
            log.error("执行工具时出错: {}", e.getMessage(), e);
            return "工具执行错误: " + e.getMessage();
        }
    }
    
    /**
     * 执行工具方法
     * 
     * @param service 服务对象
     * @param method 方法
     * @param arguments 参数
     * @return 执行结果
     */
    private String executeToolMethod(Object service, Method method, Map<String, Object> arguments) {
        try {
            // 获取方法参数类型
            Class<?>[] parameterTypes = method.getParameterTypes();
            
            if (parameterTypes.length == 0) {
                // 无参数方法
                Object result = method.invoke(service);
                return convertResultToString(result);
            } else if (parameterTypes.length == 1) {
                // 单参数方法
                Class<?> paramType = parameterTypes[0];
                
                if (Map.class.isAssignableFrom(paramType)) {
                    // 如果参数是Map类型，直接传入arguments
                    Object result = method.invoke(service, arguments);
                    return convertResultToString(result);
                } else {
                    // 需要将Map转换为对应的对象
                    Object paramInstance = objectMapper.convertValue(arguments, paramType);
                    Object result = method.invoke(service, paramInstance);
                    return convertResultToString(result);
                }
            } else {
                // 多参数方法，需要按名称匹配参数
                Object[] params = new Object[parameterTypes.length];
                
                for (int i = 0; i < parameterTypes.length; i++) {
                    Class<?> paramType = parameterTypes[i];
                    String paramName = method.getParameters()[i].getName();
                    
                    if (arguments.containsKey(paramName)) {
                        Object value = arguments.get(paramName);
                        
                        if (value != null && !paramType.isAssignableFrom(value.getClass())) {
                            // 需要类型转换
                            value = objectMapper.convertValue(value, paramType);
                        }
                        
                        params[i] = value;
                    } else {
                        // 参数未提供
                        params[i] = null;
                    }
                }
                
                Object result = method.invoke(service, params);
                return convertResultToString(result);
            }
        } catch (Exception e) {
            log.error("执行工具方法时出错: {}", e.getMessage(), e);
            return "工具执行错误: " + e.getMessage();
        }
    }
    
    /**
     * 将结果转换为字符串
     * 
     * @param result 执行结果
     * @return 字符串形式的结果
     */
    private String convertResultToString(Object result) {
        if (result == null) {
            return "null";
        }
        
        try {
            // 转换为JSON字符串
            return JSONUtil.toJsonStr(result);
        } catch (Exception e) {
            log.warn("结果转换为JSON失败，使用toString(): {}", e.getMessage());
            return result.toString();
        }
    }
} 