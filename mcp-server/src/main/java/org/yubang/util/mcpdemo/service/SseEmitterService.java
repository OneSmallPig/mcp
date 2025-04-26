package org.yubang.util.mcpdemo.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import com.google.gson.Gson;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SSE发射器服务
 * 负责管理客户端SSE连接和事件发送
 */
@Service
public class SseEmitterService {
    private static final Logger log = LoggerFactory.getLogger(SseEmitterService.class);
    private static final long SSE_TIMEOUT = 60 * 60 * 1000L; // 1小时超时
    
    private final ConcurrentHashMap<String, SseEmitter> emitters = new ConcurrentHashMap<>();
    private final Gson gson = new Gson();
    
    /**
     * 创建新的SseEmitter
     *
     * @param clientId 客户端ID
     * @return 新创建的SseEmitter
     */
    public SseEmitter createEmitter(String clientId) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);
        
        // 设置完成回调
        emitter.onCompletion(() -> {
            log.info("SSE连接完成: {}", clientId);
            removeEmitter(clientId);
        });
        
        // 设置超时回调
        emitter.onTimeout(() -> {
            log.info("SSE连接超时: {}", clientId);
            removeEmitter(clientId);
        });
        
        // 设置错误回调
        emitter.onError(ex -> {
            log.error("SSE连接错误: {}, 原因: {}", clientId, ex.getMessage());
            removeEmitter(clientId);
        });
        
        emitters.put(clientId, emitter);
        log.info("创建SSE连接: {}，当前连接数: {}", clientId, emitters.size());
        
        return emitter;
    }
    
    /**
     * 移除SseEmitter
     *
     * @param clientId 客户端ID
     */
    public void removeEmitter(String clientId) {
        SseEmitter emitter = emitters.remove(clientId);
        if (emitter != null) {
            emitter.complete();
            log.info("移除SSE连接: {}，剩余连接数: {}", clientId, emitters.size());
        }
    }
    
    /**
     * 检查是否存在指定客户端的SseEmitter
     *
     * @param clientId 客户端ID
     * @return 是否存在
     */
    public boolean hasEmitter(String clientId) {
        return emitters.containsKey(clientId);
    }
    
    /**
     * 发送SSE事件
     *
     * @param clientId 客户端ID
     * @param eventType 事件类型
     * @param data 事件数据
     * @throws IOException 发送异常
     */
    public void sendEvent(String clientId, String eventType, Object data) throws IOException {
        SseEmitter emitter = emitters.get(clientId);
        if (emitter == null) {
            log.warn("尝试向不存在的客户端发送事件: {}", clientId);
            return;
        }
        
        String jsonData = data instanceof String ? (String) data : gson.toJson(data);
        
        try {
            emitter.send(SseEmitter.event()
                    .name(eventType)
                    .data(jsonData, org.springframework.http.MediaType.APPLICATION_JSON));
            
            log.debug("发送事件 [{}] 到客户端 {}: {}", eventType, clientId, jsonData);
        } catch (Exception e) {
            log.error("发送事件失败: {}", e.getMessage());
            removeEmitter(clientId);
            throw e;
        }
    }
    
    /**
     * 广播事件到所有客户端
     *
     * @param eventType 事件类型
     * @param data 事件数据
     */
    public void broadcastEvent(String eventType, Object data) {
        emitters.forEach((clientId, emitter) -> {
            try {
                sendEvent(clientId, eventType, data);
            } catch (IOException e) {
                log.error("广播事件到客户端 {} 失败: {}", clientId, e.getMessage());
            }
        });
    }
} 