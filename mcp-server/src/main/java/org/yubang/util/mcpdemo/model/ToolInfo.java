package org.yubang.util.mcpdemo.model;

import lombok.Data;
import java.util.List;
import java.util.Map;

/**
 * 工具信息类
 * 用于返回工具的名称、描述等基本信息
 */
@Data
public class ToolInfo {
    
    /**
     * 工具名称
     */
    private String name;
    
    /**
     * 工具描述
     */
    private String description;
    
    /**
     * 工具方法名
     */
    private String methodName;
    
    /**
     * 工具所在类名
     */
    private String className;
    
    /**
     * 工具参数列表
     * 每个参数包含name、type、description和required属性
     */
    private List<Map<String, Object>> parameters;
} 