package org.yubang.util.mcpdemo.model;

import lombok.Data;

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
} 