package com.example.mcp.client.util;

import com.example.mcp.client.model.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JAR工具扫描器，用于从JAR文件中扫描工具注解
 * 直接分析JAR文件内容，而不加载类
 */
public class JarToolScanner {
    private static final Logger log = LoggerFactory.getLogger(JarToolScanner.class);
    
    // 用于匹配Tool注解的正则表达式
    private static final Pattern TOOL_ANNOTATION_PATTERN = 
            Pattern.compile("@(?:org\\.springframework\\.ai\\.tool\\.annotation\\.)?Tool\\s*\\(\\s*" +
                    "(?:(?:name\\s*=\\s*\"([^\"]+)\")|(?:description\\s*=\\s*\"([^\"]+)\")|(?:[^,]*))[,\\s]*" +
                    "(?:(?:name\\s*=\\s*\"([^\"]+)\")|(?:description\\s*=\\s*\"([^\"]+)\")|(?:[^,]*))[,\\s]*" +
                    "(?:(?:name\\s*=\\s*\"([^\"]+)\")|(?:description\\s*=\\s*\"([^\"]+)\"))?");
    
    // 用于匹配方法定义的正则表达式
    private static final Pattern METHOD_PATTERN = 
            Pattern.compile("(?:public|private|protected)?\\s+(?:static\\s+)?\\w+\\s+(\\w+)\\s*\\(([^)]*)\\)");
    
    /**
     * 扫描JAR文件中的工具
     * 
     * @param jarPath JAR文件路径
     * @return 工具列表
     */
    public static List<Tool> scanTools(String jarPath) {
        log.info("开始扫描JAR文件中的工具: {}", jarPath);
        List<Tool> tools = new ArrayList<>();
        
        try {
            File jarFile = new File(jarPath);
            if (!jarFile.exists()) {
                log.error("JAR文件不存在: {}", jarPath);
                return tools;
            }
            
            // 检查是否是Spring Boot JAR
            boolean isSpringBootJar = isSpringBootJar(jarFile);
            if (isSpringBootJar) {
                log.info("检测到Spring Boot JAR文件，将使用特殊处理逻辑");
            }
            
            // 使用直接读取JAR文件内容的方式
            try (JarFile jar = new JarFile(jarFile)) {
                // 遍历JAR中的Java源文件或类文件，寻找可能包含工具注解的文件
                Enumeration<JarEntry> entries = jar.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    String entryName = entry.getName();
                    
                    // 只处理Java源文件或服务类实现
                    if (entryName.endsWith(".java") || 
                        (entryName.endsWith(".class") && 
                         (entryName.contains("Service") || 
                          entryName.contains("Tool") || 
                          entryName.contains("Controller")))) {
                        
                        // 处理Java文件，查找Tool注解
                        if (entryName.contains("mcpdemo")) {
                            List<Tool> foundTools = scanFileForToolAnnotations(jar, entry);
                            if (!foundTools.isEmpty()) {
                                tools.addAll(foundTools);
                                log.info("在 {} 中找到 {} 个工具", entryName, foundTools.size());
                            }
                        }
                    }
                }
                
                // 如果没有找到工具，尝试从Spring Boot特定位置查找工具描述文件
                if (tools.isEmpty() && isSpringBootJar) {
                    tools.addAll(scanSpringBootMetadata(jar));
                }
            }
        } catch (IOException e) {
            log.error("扫描JAR文件失败", e);
        }
        
        return tools;
    }
    
    /**
     * 检查是否是Spring Boot JAR文件
     */
    private static boolean isSpringBootJar(File jarFile) {
        try (JarFile jar = new JarFile(jarFile)) {
            return jar.getEntry("BOOT-INF/") != null;
        } catch (IOException e) {
            log.error("检查JAR文件类型失败", e);
            return false;
        }
    }
    
    /**
     * 扫描文件中的Tool注解
     */
    private static List<Tool> scanFileForToolAnnotations(JarFile jar, JarEntry entry) {
        List<Tool> tools = new ArrayList<>();
        
        try (InputStream is = jar.getInputStream(entry);
             BufferedInputStream bis = new BufferedInputStream(is)) {
            
            // 读取文件内容
            byte[] buffer = new byte[(int) entry.getSize()];
            bis.read(buffer);
            String content = new String(buffer);
            
            // 使用不同的策略查找工具
            tools.addAll(findToolAnnotations(content));
            
            if (tools.isEmpty()) {
                tools.addAll(findSpringAiTools(content, entry.getName()));
            }
            
            if (tools.isEmpty() && (isServiceClass(entry.getName()) || isControllerClass(entry.getName()))) {
                tools.addAll(inferToolsFromMethods(content, entry.getName()));
            }
            
        } catch (IOException e) {
            log.warn("读取JAR条目失败: {}", entry.getName());
        }
        
        return tools;
    }
    
    /**
     * 查找Tool注解
     */
    private static List<Tool> findToolAnnotations(String content) {
        List<Tool> tools = new ArrayList<>();
        
        try {
            // 使用正则表达式查找Tool注解
            Matcher matcher = TOOL_ANNOTATION_PATTERN.matcher(content);
            while (matcher.find()) {
                String name = null;
                String description = null;
                
                // 尝试从不同的匹配组中提取name和description
                for (int i = 1; i <= matcher.groupCount(); i++) {
                    String value = matcher.group(i);
                    if (value != null && !value.isEmpty()) {
                        // 检查是否是前面包含name=的组
                        if (i % 2 == 1 && name == null) {
                            name = value;
                        } 
                        // 检查是否是前面包含description=的组
                        else if (i % 2 == 0 && description == null) {
                            description = value;
                        }
                    }
                }
                
                // 还可以尝试另一种方式查找注解属性
                if (name == null || description == null) {
                    // 提取注解内容
                    String annotationContent = matcher.group(0);
                    
                    // 尝试提取name
                    if (name == null) {
                        Pattern namePattern = Pattern.compile("name\\s*=\\s*\"([^\"]+)\"");
                        Matcher nameMatcher = namePattern.matcher(annotationContent);
                        if (nameMatcher.find()) {
                            name = nameMatcher.group(1);
                        }
                    }
                    
                    // 尝试提取description
                    if (description == null) {
                        Pattern descPattern = Pattern.compile("description\\s*=\\s*\"([^\"]+)\"");
                        Matcher descMatcher = descPattern.matcher(annotationContent);
                        if (descMatcher.find()) {
                            description = descMatcher.group(1);
                        }
                    }
                }
                
                // 提取方法名称作为后备工具名称
                if (name == null) {
                    String methodSection = content.substring(matcher.end());
                    Pattern methodNamePattern = Pattern.compile("\\s+(\\w+)\\s*\\(");
                    Matcher methodNameMatcher = methodNamePattern.matcher(methodSection);
                    if (methodNameMatcher.find()) {
                        name = methodNameMatcher.group(1);
                    }
                }
                
                if (name != null) {
                    Tool tool = new Tool();
                    tool.setName(name);
                    tool.setDescription(description != null ? description : "");
                    
                    // 尝试查找与工具匹配的方法
                    Pattern methodPattern = Pattern.compile(
                            "(?:public|private|protected)?\\s+(?:static\\s+)?\\w+\\s+" + 
                            name + "\\s*\\(([^)]+)\\)");
                    
                    Matcher methodMatcher = methodPattern.matcher(content);
                    if (methodMatcher.find()) {
                        String paramList = methodMatcher.group(1);
                        Map<String, Object> parameters = parseMethodParameters(paramList);
                        if (!parameters.isEmpty()) {
                            tool.setParameters(parameters);
                        }
                    }
                    
                    tools.add(tool);
                    log.info("通过注解发现工具: {} - {}", name, description);
                }
            }
            
        } catch (Exception e) {
            log.warn("查找Tool注解失败: {}", e.getMessage());
        }
        
        return tools;
    }
    
    /**
     * 查找Spring AI工具
     */
    private static List<Tool> findSpringAiTools(String content, String filename) {
        List<Tool> tools = new ArrayList<>();
        
        try {
            // 查找类名
            String className = extractClassName(filename);
            if (className.isEmpty()) {
                return tools;
            }
            
            // 查找@Tool注解的方法
            Pattern toolMethodPattern = Pattern.compile(
                    "@(?:Tool|org\\.springframework\\.ai\\.tool\\.annotation\\.Tool)\\s*(?:\\([^)]*\\))?\\s*\n" +
                    "\\s*(?:public|private|protected)?\\s+(?:static\\s+)?\\w+\\s+(\\w+)\\s*\\(([^)]*)\\)");
            
            Matcher toolMethodMatcher = toolMethodPattern.matcher(content);
            
            while (toolMethodMatcher.find()) {
                String methodName = toolMethodMatcher.group(1);
                String paramList = toolMethodMatcher.group(2);
                
                Tool tool = new Tool();
                
                // 尝试在上下文中查找注解的name和description
                String contextBefore = content.substring(
                        Math.max(0, toolMethodMatcher.start() - 200), 
                        toolMethodMatcher.start());
                
                // 查找name
                Pattern namePattern = Pattern.compile("name\\s*=\\s*\"([^\"]+)\"");
                Matcher nameMatcher = namePattern.matcher(contextBefore);
                if (nameMatcher.find()) {
                    tool.setName(nameMatcher.group(1));
                } else {
                    tool.setName(methodName);
                }
                
                // 查找description
                Pattern descPattern = Pattern.compile("description\\s*=\\s*\"([^\"]+)\"");
                Matcher descMatcher = descPattern.matcher(contextBefore);
                if (descMatcher.find()) {
                    tool.setDescription(descMatcher.group(1));
                } else {
                    tool.setDescription(formatMethodName(methodName));
                }
                
                // 解析参数
                Map<String, Object> parameters = parseMethodParameters(paramList);
                if (!parameters.isEmpty()) {
                    tool.setParameters(parameters);
                }
                
                tools.add(tool);
                log.info("通过Spring AI注解发现工具: {} - {}", tool.getName(), tool.getDescription());
            }
            
        } catch (Exception e) {
            log.warn("查找Spring AI工具失败: {}", e.getMessage());
        }
        
        return tools;
    }
    
    /**
     * 从文件名提取类名
     */
    private static String extractClassName(String filename) {
        if (filename.contains("/")) {
            filename = filename.substring(filename.lastIndexOf('/') + 1);
        }
        if (filename.endsWith(".class") || filename.endsWith(".java")) {
            filename = filename.substring(0, filename.lastIndexOf('.'));
        }
        return filename;
    }
    
    /**
     * 格式化方法名为描述文字
     */
    private static String formatMethodName(String methodName) {
        return methodName
                .replaceAll("([A-Z])", " $1")
                .toLowerCase()
                .trim();
    }
    
    /**
     * 解析方法参数列表
     */
    private static Map<String, Object> parseMethodParameters(String paramList) {
        Map<String, Object> parameters = new HashMap<>();
        
        if (paramList == null || paramList.trim().isEmpty()) {
            return parameters;
        }
        
        Map<String, Object> properties = new HashMap<>();
        List<String> required = new ArrayList<>();
        
        // 解析参数列表
        String[] params = paramList.split(",");
        for (String param : params) {
            param = param.trim();
            if (param.isEmpty()) continue;
            
            String[] parts = param.split("\\s+");
            if (parts.length >= 2) {
                String paramType = parts[0];
                String paramName = parts[parts.length - 1];
                
                // 去除可能的注解或其他修饰符
                if (paramName.contains("@")) {
                    paramName = paramName.substring(paramName.lastIndexOf('@') + 1);
                }
                
                // 去除可能的分号
                if (paramName.endsWith(";")) {
                    paramName = paramName.substring(0, paramName.length() - 1);
                }
                
                Map<String, Object> paramInfo = new HashMap<>();
                paramInfo.put("type", inferJsonType(paramType));
                paramInfo.put("description", paramName);
                
                properties.put(paramName, paramInfo);
                required.add(paramName);
            }
        }
        
        if (!properties.isEmpty()) {
            parameters.put("type", "object");
            parameters.put("properties", properties);
            parameters.put("required", required);
        }
        
        return parameters;
    }
    
    /**
     * 判断是否是服务类
     */
    private static boolean isServiceClass(String filename) {
        return filename.contains("Service") || 
               filename.contains("service") || 
               filename.contains("Tool") || 
               filename.contains("tool");
    }
    
    /**
     * 判断是否是控制器类
     */
    private static boolean isControllerClass(String filename) {
        return filename.contains("Controller") || 
               filename.contains("controller") || 
               filename.contains("Resource") || 
               filename.contains("resource");
    }
    
    /**
     * 从类方法推断可能的工具
     */
    private static List<Tool> inferToolsFromMethods(String content, String className) {
        List<Tool> tools = new ArrayList<>();
        
        // 从类名中提取可能的工具前缀
        String simpleClassName = extractClassName(className);
        String toolPrefix = "";
        
        if (simpleClassName.endsWith("Service")) {
            toolPrefix = simpleClassName.substring(0, simpleClassName.length() - 7).toLowerCase();
        } else if (simpleClassName.endsWith("Tool")) {
            toolPrefix = simpleClassName.substring(0, simpleClassName.length() - 4).toLowerCase();
        } else if (simpleClassName.endsWith("Controller")) {
            toolPrefix = simpleClassName.substring(0, simpleClassName.length() - 10).toLowerCase();
        }
        
        // 查找公共方法
        Pattern methodPattern = Pattern.compile(
                "(?:public|protected)\\s+(?:static\\s+)?\\w+\\s+(\\w+)\\s*\\(([^)]*)\\)");
        
        Matcher methodMatcher = methodPattern.matcher(content);
        while (methodMatcher.find()) {
            String methodName = methodMatcher.group(1);
            String paramList = methodMatcher.group(2);
            
            // 跳过常见的非工具方法
            if (methodName.equals("equals") || methodName.equals("toString") || 
                methodName.equals("hashCode") || methodName.startsWith("get") || 
                methodName.startsWith("set") || methodName.startsWith("is")) {
                continue;
            }
            
            // 创建工具对象
            Tool tool = new Tool();
            
            // 设置工具名称，优先使用前缀
            if (!toolPrefix.isEmpty()) {
                tool.setName(toolPrefix + "_" + methodName);
            } else {
                tool.setName(methodName);
            }
            
            // 从方法名生成描述
            tool.setDescription(formatMethodName(methodName));
            
            // 解析参数
            Map<String, Object> parameters = parseMethodParameters(paramList);
            if (!parameters.isEmpty()) {
                tool.setParameters(parameters);
            }
            
            tools.add(tool);
            log.info("推断工具: {} - {}", tool.getName(), tool.getDescription());
        }
        
        return tools;
    }
    
    /**
     * 扫描Spring Boot元数据寻找工具信息
     */
    private static List<Tool> scanSpringBootMetadata(JarFile jar) {
        List<Tool> tools = new ArrayList<>();
        
        try {
            // 检查Spring Boot自定义工具配置
            JarEntry toolConfigEntry = jar.getJarEntry("BOOT-INF/classes/mcp-tools.properties");
            if (toolConfigEntry == null) {
                toolConfigEntry = jar.getJarEntry("mcp-tools.properties");
            }
            
            if (toolConfigEntry != null) {
                // 读取工具配置
                try (InputStream is = jar.getInputStream(toolConfigEntry)) {
                    Map<String, String> toolConfig = readPropertiesFile(is);
                    
                    // 解析工具信息
                    for (Map.Entry<String, String> entry : toolConfig.entrySet()) {
                        String key = entry.getKey();
                        String value = entry.getValue();
                        
                        if (key.endsWith(".name")) {
                            String toolId = key.substring(0, key.length() - 5);
                            Tool tool = new Tool();
                            tool.setName(value);
                            
                            // 查找对应的描述
                            String description = toolConfig.get(toolId + ".description");
                            tool.setDescription(description != null ? description : "");
                            
                            // 查找对应的参数
                            String params = toolConfig.get(toolId + ".parameters");
                            if (params != null && !params.isEmpty()) {
                                Map<String, Object> parameters = parseParametersString(params);
                                tool.setParameters(parameters);
                            }
                            
                            tools.add(tool);
                            log.info("从配置中发现工具: {} - {}", value, description);
                        }
                    }
                }
            }
            
            // 如果仍未找到工具，尝试读取服务类元数据
            if (tools.isEmpty()) {
                for (Enumeration<JarEntry> entries = jar.entries(); entries.hasMoreElements();) {
                    JarEntry entry = entries.nextElement();
                    String entryName = entry.getName();
                    
                    // 查找服务实现类
                    if (entryName.startsWith("BOOT-INF/classes/META-INF/services/")) {
                        try (InputStream is = jar.getInputStream(entry)) {
                            byte[] buffer = new byte[(int) entry.getSize()];
                            is.read(buffer);
                            String serviceClass = new String(buffer).trim();
                            
                            // 查找对应的类文件
                            String classPath = "BOOT-INF/classes/" + serviceClass.replace('.', '/') + ".class";
                            JarEntry classEntry = jar.getJarEntry(classPath);
                            if (classEntry != null) {
                                List<Tool> serviceTools = scanFileForToolAnnotations(jar, classEntry);
                                tools.addAll(serviceTools);
                            }
                        }
                    }
                }
            }
        } catch (IOException e) {
            log.warn("扫描Spring Boot元数据失败", e);
        }
        
        return tools;
    }
    
    /**
     * 读取属性文件
     */
    private static Map<String, String> readPropertiesFile(InputStream is) throws IOException {
        Map<String, String> properties = new HashMap<>();
        
        try (BufferedInputStream bis = new BufferedInputStream(is)) {
            byte[] buffer = new byte[bis.available()];
            bis.read(buffer);
            String content = new String(buffer);
            
            for (String line : content.split("\n")) {
                line = line.trim();
                if (!line.isEmpty() && !line.startsWith("#")) {
                    int pos = line.indexOf('=');
                    if (pos > 0) {
                        String key = line.substring(0, pos).trim();
                        String value = line.substring(pos + 1).trim();
                        properties.put(key, value);
                    }
                }
            }
        }
        
        return properties;
    }
    
    /**
     * 解析参数字符串
     */
    private static Map<String, Object> parseParametersString(String params) {
        Map<String, Object> parameters = new HashMap<>();
        Map<String, Object> properties = new HashMap<>();
        List<String> required = new ArrayList<>();
        
        for (String param : params.split(",")) {
            param = param.trim();
            String[] parts = param.split(":");
            if (parts.length >= 1) {
                String paramName = parts[0].trim();
                String paramType = parts.length > 1 ? parts[1].trim() : "string";
                
                Map<String, Object> paramInfo = new HashMap<>();
                paramInfo.put("type", paramType);
                paramInfo.put("description", paramName);
                
                properties.put(paramName, paramInfo);
                required.add(paramName);
            }
        }
        
        if (!properties.isEmpty()) {
            parameters.put("type", "object");
            parameters.put("properties", properties);
            parameters.put("required", required);
        }
        
        return parameters;
    }
    
    /**
     * 根据Java类型推断JSON Schema类型
     */
    private static String inferJsonType(String javaType) {
        javaType = javaType.trim();
        
        if (javaType.equals("String") || javaType.equals("java.lang.String")) {
            return "string";
        } else if (javaType.equals("int") || javaType.equals("Integer") || 
                   javaType.equals("long") || javaType.equals("Long") ||
                   javaType.equals("short") || javaType.equals("Short") ||
                   javaType.equals("byte") || javaType.equals("Byte")) {
            return "integer";
        } else if (javaType.equals("double") || javaType.equals("Double") ||
                   javaType.equals("float") || javaType.equals("Float")) {
            return "number";
        } else if (javaType.equals("boolean") || javaType.equals("Boolean")) {
            return "boolean";
        } else if (javaType.contains("List") || javaType.contains("[]") || 
                   javaType.contains("Array") || javaType.contains("Collection")) {
            return "array";
        } else {
            return "object";
        }
    }
} 