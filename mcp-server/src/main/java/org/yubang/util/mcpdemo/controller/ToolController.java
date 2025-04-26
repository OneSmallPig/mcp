package org.yubang.util.mcpdemo.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.yubang.util.mcpdemo.model.ToolInfo;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
                    
                    // 获取方法参数信息，处理复杂Bean对象
                    Parameter[] parameters = method.getParameters();
                    if (parameters.length > 0) {
                        toolInfo.setParameters(processParameters(parameters));
                    }
                    
                    toolInfoList.add(toolInfo);
                }
            }
        }
        
        return toolInfoList;
    }
    
    /**
     * 处理方法参数，如果参数是复杂对象，则展开其字段
     */
    private List<Map<String, Object>> processParameters(Parameter[] parameters) {
        List<Map<String, Object>> paramsList = new ArrayList<>();
        
        for (Parameter param : parameters) {
            Class<?> paramType = param.getType();
            
            // 如果是简单类型，直接作为一个参数
            if (isSimpleType(paramType)) {
                Map<String, Object> paramInfo = createParamInfo(param.getName(), paramType, "参数: " + param.getName(), true);
                paramsList.add(paramInfo);
            } 
            // 如果是复杂Bean类型，获取其所有字段
            else {
                try {
                    Field[] fields = paramType.getDeclaredFields();
                    for (Field field : fields) {
                        field.setAccessible(true);
                        
                        // 跳过静态字段和常量
                        if (Modifier.isStatic(field.getModifiers()) || 
                            (Modifier.isFinal(field.getModifiers()) && Modifier.isStatic(field.getModifiers()))) {
                            continue;
                        }
                        
                        String fieldName = field.getName();
                        Class<?> fieldType = field.getType();
                        
                        // 使用字段名称和类型作为描述
                        String description = "参数: " + fieldName + " (" + fieldType.getSimpleName() + ")";
                        
                        // 获取字段默认值（如果有）
                        Object defaultValue = null;
                        try {
                            // 创建一个新实例来检查默认值
                            Object instance = paramType.getDeclaredConstructor().newInstance();
                            defaultValue = field.get(instance);
                        } catch (Exception e) {
                            // 忽略，如果无法创建实例或获取值
                        }
                        
                        // 判断是否为必需字段
                        boolean required = isRequiredField(field, defaultValue);
                        
                        // 创建字段参数信息
                        Map<String, Object> fieldParamInfo = createParamInfo(fieldName, fieldType, description, required);
                        
                        // 如果有默认值，添加到参数信息中
                        if (defaultValue != null && !isDefaultValueEmpty(defaultValue)) {
                            fieldParamInfo.put("default", defaultValue.toString());
                        }
                        
                        paramsList.add(fieldParamInfo);
                    }
                } catch (Exception e) {
                    // 如果反射失败，仍然将整个对象作为一个参数
                    Map<String, Object> paramInfo = createParamInfo(param.getName(), paramType, "参数: " + param.getName(), true);
                    paramsList.add(paramInfo);
                }
            }
        }
        
        return paramsList;
    }
    
    /**
     * 创建参数信息对象
     */
    private Map<String, Object> createParamInfo(String name, Class<?> type, String description, boolean required) {
        Map<String, Object> paramInfo = new HashMap<>();
        paramInfo.put("name", name);
        paramInfo.put("type", mapJavaTypeToJsonType(type));
        paramInfo.put("description", description);
        paramInfo.put("required", required);
        return paramInfo;
    }
    
    /**
     * 判断类型是否为简单类型
     */
    private boolean isSimpleType(Class<?> type) {
        return type.isPrimitive() || 
               type == String.class || 
               Number.class.isAssignableFrom(type) ||
               Boolean.class == type ||
               type.isEnum();
    }
    
    /**
     * 判断字段是否为必需字段
     */
    private boolean isRequiredField(Field field, Object defaultValue) {
        // 基本类型一般是必需的
        if (field.getType().isPrimitive()) {
            return true;
        }
        
        // 如果没有默认值或默认值为null，一般认为是必需的
        if (defaultValue == null) {
            return true;
        }
        
        // 字符串类型，如果默认为空字符串，也可能是必需的
        if (defaultValue instanceof String && ((String) defaultValue).isEmpty()) {
            return true;
        }
        
        // 其他情况，有默认值的字段通常不是必需的
        return false;
    }
    
    /**
     * 判断默认值是否为空（对于集合、字符串等）
     */
    private boolean isDefaultValueEmpty(Object value) {
        if (value instanceof String) {
            return ((String) value).isEmpty();
        }
        if (value instanceof Collection) {
            return ((Collection<?>) value).isEmpty();
        }
        if (value instanceof Map) {
            return ((Map<?, ?>) value).isEmpty();
        }
        return false;
    }
    
    /**
     * 将Java类型映射为JSON类型
     * @param type Java类型
     * @return 对应的JSON类型
     */
    private String mapJavaTypeToJsonType(Class<?> type) {
        if (type == String.class) {
            return "string";
        } else if (type == Integer.class || type == int.class || 
                  type == Long.class || type == long.class ||
                  type == Short.class || type == short.class ||
                  type == Byte.class || type == byte.class) {
            return "number";
        } else if (type == Boolean.class || type == boolean.class) {
            return "boolean";
        } else if (type.isArray() || List.class.isAssignableFrom(type)) {
            return "array";
        } else if (Map.class.isAssignableFrom(type)) {
            return "object";
        } else {
            // 复杂对象或其他类型，默认为对象
            return "object";
        }
    }
} 