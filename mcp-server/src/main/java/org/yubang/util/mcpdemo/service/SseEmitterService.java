package org.yubang.util.mcpdemo.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import com.google.gson.Gson;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

/**
 * SSE发射器服务
 * 管理所有的SSE连接并提供发送事件的方法
 */
@Service
public class SseEmitterService {
    private static final Logger log = LoggerFactory.getLogger(SseEmitterService.class);
    
    // 默认SSE连接过期时间：12小时
    private static final long DEFAULT_TIMEOUT = 12 * 60 * 60 * 1000;
    
    // 心跳间隔：30秒
    private static final long HEARTBEAT_INTERVAL = 30 * 1000;
    
    // 使用ConcurrentHashMap存储SSE发射器
    private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();
    
    // JSON序列化工具
    private final Gson gson = new Gson();
    
    // 心跳任务执行器
    private ScheduledExecutorService heartbeatExecutor;
    
    @PostConstruct
    public void init() {
        heartbeatExecutor = Executors.newSingleThreadScheduledExecutor();
        // 启动定时心跳任务
        heartbeatExecutor.scheduleAtFixedRate(
            this::sendHeartbeats, 
            HEARTBEAT_INTERVAL, 
            HEARTBEAT_INTERVAL, 
            TimeUnit.MILLISECONDS
        );
        log.info("SSE服务初始化完成，心跳间隔：{}ms", HEARTBEAT_INTERVAL);
    }
    
    @PreDestroy
    public void destroy() {
        if (heartbeatExecutor != null) {
            heartbeatExecutor.shutdownNow();
            log.info("SSE心跳服务已关闭");
        }
        
        // 关闭所有连接
        emitters.forEach((clientId, emitter) -> {
            emitter.complete();
            log.info("关闭客户端 {} 的SSE连接", clientId);
        });
        emitters.clear();
    }
    
    /**
     * 创建一个新的SSE发射器
     * 
     * @param clientId 客户端ID
     * @return 创建的SSE发射器
     */
    public SseEmitter createEmitter(String clientId) {
        // 如果已存在，先移除旧的连接
        removeEmitter(clientId);
        
        SseEmitter emitter = new SseEmitter(DEFAULT_TIMEOUT);
        
        // 添加完成回调
        emitter.onCompletion(() -> {
            log.info("客户端 {} 的SSE连接已完成", clientId);
            removeEmitter(clientId);
        });
        
        // 添加超时回调
        emitter.onTimeout(() -> {
            log.info("客户端 {} 的SSE连接已超时", clientId);
            removeEmitter(clientId);
        });
        
        // 添加错误回调
        emitter.onError((ex) -> {
            log.warn("客户端 {} 的SSE连接发生错误: {}", clientId, ex.getMessage());
            removeEmitter(clientId);
        });
        
        emitters.put(clientId, emitter);
        log.info("已为客户端 {} 创建SSE连接，当前活跃连接数: {}", clientId, emitters.size());
        
        return emitter;
    }
    
    /**
     * 移除SSE发射器
     * 
     * @param clientId 客户端ID
     */
    public void removeEmitter(String clientId) {
        SseEmitter emitter = emitters.remove(clientId);
        if (emitter != null) {
            try {
                emitter.complete();
                log.info("已移除客户端 {} 的SSE连接，当前活跃连接数: {}", clientId, emitters.size());
            } catch (Exception e) {
                log.warn("移除客户端 {} 的SSE连接时发生错误", clientId, e);
            }
        }
    }
    
    /**
     * 判断是否存在指定客户端的发射器
     * 
     * @param clientId 客户端ID
     * @return 是否存在
     */
    public boolean hasEmitter(String clientId) {
        return emitters.containsKey(clientId);
    }
    
    /**
     * 发送事件
     * 
     * @param clientId 客户端ID
     * @param eventName 事件名称
     * @param data 事件数据
     * @throws IOException 发送异常
     */
    public void sendEvent(String clientId, String eventName, Object data) throws IOException {
        SseEmitter emitter = emitters.get(clientId);
        if (emitter == null) {
            String errorMsg = "客户端 " + clientId + " 的SSE连接不存在";
            log.warn(errorMsg);
            throw new IOException(errorMsg);
        }
        
        // 创建SSE事件对象
        String jsonData = gson.toJson(data);
        SseEmitter.SseEventBuilder event = SseEmitter.event()
            .id(String.valueOf(System.currentTimeMillis()))
            .name(eventName)
            .data(jsonData);
        
        // 发送事件
        try {
            emitter.send(event);
            log.debug("已向客户端 {} 发送事件: {}, 数据: {}", clientId, eventName, jsonData);
        } catch (Exception e) {
            log.error("向客户端 {} 发送事件 {} 失败: {}", clientId, eventName, e.getMessage());
            removeEmitter(clientId);
            throw e;
        }
    }
    
    /**
     * 安全发送事件
     * 不抛出异常，而是返回发送状态
     * 
     * @param clientId 客户端ID
     * @param eventName 事件名称
     * @param data 事件数据
     * @return 发送是否成功
     */
    public boolean sendEventSafely(String clientId, String eventName, Object data) {
        try {
            sendEvent(clientId, eventName, data);
            return true;
        } catch (Exception e) {
            log.warn("安全发送事件失败: clientId={}, event={}, error={}", clientId, eventName, e.getMessage());
            return false;
        }
    }
    
    /**
     * 向所有客户端发送事件
     * 
     * @param eventName 事件名称
     * @param data 事件数据
     */
    public void broadcastEvent(String eventName, Object data) {
        log.info("广播事件 {} 给 {} 个客户端", eventName, emitters.size());
        
        // 记录失败的客户端，稍后移除
        Map<String, Exception> failedClients = new ConcurrentHashMap<>();
        
        // 向每个客户端发送事件
        emitters.forEach((clientId, emitter) -> {
            try {
                sendEvent(clientId, eventName, data);
            } catch (Exception e) {
                failedClients.put(clientId, e);
            }
        });
        
        // 移除失败的客户端
        failedClients.forEach((clientId, exception) -> {
            log.warn("向客户端 {} 广播事件失败: {}", clientId, exception.getMessage());
            removeEmitter(clientId);
        });
    }
    
    /**
     * 定时发送心跳
     * 定期向所有客户端发送心跳消息，保持连接活跃
     */
    private void sendHeartbeats() {
        if (emitters.isEmpty()) {
            return;
        }
        
        log.debug("正在向 {} 个客户端发送心跳...", emitters.size());
        
        Map<String, Object> heartbeatData = Map.of(
            "type", "heartbeat",
            "timestamp", System.currentTimeMillis()
        );
        
        // 记录失败的客户端，稍后移除
        Map<String, Exception> failedClients = new ConcurrentHashMap<>();
        
        // 向每个客户端发送心跳
        emitters.forEach((clientId, emitter) -> {
            try {
                sendEvent(clientId, "heartbeat", heartbeatData);
            } catch (Exception e) {
                failedClients.put(clientId, e);
            }
        });
        
        // 移除失败的客户端
        failedClients.forEach((clientId, exception) -> {
            log.warn("向客户端 {} 发送心跳失败: {}", clientId, exception.getMessage());
            removeEmitter(clientId);
        });
        
        if (!failedClients.isEmpty()) {
            log.info("心跳检测: {} 个客户端连接已断开，当前活跃连接数: {}", 
                failedClients.size(), emitters.size());
        }
    }
    
    /**
     * 获取当前活跃连接数
     * 
     * @return 活跃连接数
     */
    public int getActiveConnectionCount() {
        return emitters.size();
    }
} 