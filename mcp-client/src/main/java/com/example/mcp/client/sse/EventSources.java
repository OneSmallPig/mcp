package com.example.mcp.client.sse;

import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * SSE连接工厂，基于OkHttp实现简单的SSE客户端
 */
public final class EventSources {
    private static final Logger log = LoggerFactory.getLogger(EventSources.class);
    
    private EventSources() {
        // 工具类，不允许实例化
    }
    
    /**
     * 创建EventSource工厂
     * 
     * @param client OkHttp客户端
     * @return 工厂实例
     */
    public static EventSource.Factory createFactory(OkHttpClient client) {
        return new Factory(client);
    }
    
    /**
     * EventSource实现
     */
    static final class RealEventSource implements EventSource {
        private final OkHttpClient client;
        private final Request request;
        private final EventSourceListener listener;
        private Call call;
        private boolean canceled = false;
        
        RealEventSource(OkHttpClient client, Request request, EventSourceListener listener) {
            this.client = client;
            this.request = request;
            this.listener = listener;
        }
        
        @Override
        public void connect() {
            Request request = this.request.newBuilder()
                    .header("Accept", "text/event-stream")
                    .build();
            
            call = client.newCall(request);
            call.enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    if (!canceled) {
                        listener.onFailure(RealEventSource.this, e, null);
                    }
                }
                
                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    try {
                        if (!response.isSuccessful()) {
                            listener.onFailure(RealEventSource.this, 
                                    new IOException("Unexpected response: " + response), 
                                    response);
                            return;
                        }
                        
                        listener.onOpen(RealEventSource.this, response);
                        processEvents(response);
                    } catch (Exception e) {
                        if (!canceled) {
                            listener.onFailure(RealEventSource.this, e, response);
                        }
                    } finally {
                        response.close();
                    }
                }
            });
        }
        
        private void processEvents(Response response) throws IOException {
            if (response.body() == null) {
                return;
            }
            
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(response.body().byteStream(), StandardCharsets.UTF_8));
            
            String line;
            StringBuilder dataBuilder = new StringBuilder();
            String lastEventId = "";
            String eventType = "message";
            
            while (!canceled && (line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    // 空行表示事件的结束
                    if (dataBuilder.length() > 0) {
                        String data = dataBuilder.toString();
                        listener.onEvent(this, lastEventId, eventType, data);
                        dataBuilder.setLength(0);
                        eventType = "message"; // 重置为默认事件类型
                    }
                    continue;
                }
                
                if (line.startsWith("id:")) {
                    lastEventId = line.substring(3).trim();
                } else if (line.startsWith("event:")) {
                    eventType = line.substring(6).trim();
                } else if (line.startsWith("data:")) {
                    dataBuilder.append(line.substring(5).trim());
                    dataBuilder.append("\n");
                } else if (line.startsWith("retry:")) {
                    // 重试时间处理，这里简化实现
                } else {
                    // 忽略未知行
                }
            }
            
            // 确保处理完最后一个事件
            if (!canceled && dataBuilder.length() > 0) {
                String data = dataBuilder.toString();
                listener.onEvent(this, lastEventId, eventType, data);
            }
            
            if (!canceled) {
                listener.onClosed(this);
            }
        }
        
        @Override
        public void cancel() {
            canceled = true;
            if (call != null) {
                call.cancel();
            }
        }
        
        @Override
        public boolean isClosed() {
            return canceled || (call != null && call.isCanceled());
        }
        
        @Override
        public void close() throws IOException {
            cancel();
        }
    }
    
    /**
     * EventSource工厂实现
     */
    static final class Factory implements EventSource.Factory {
        private final OkHttpClient client;
        
        Factory(OkHttpClient client) {
            this.client = client;
        }
        
        @Override
        public EventSource newEventSource(Request request, EventSourceListener listener) {
            RealEventSource eventSource = new RealEventSource(client, request, listener);
            eventSource.connect();
            return eventSource;
        }
    }
} 