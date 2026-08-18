package com.utc.blog.log.sdk.logback;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.AppenderBase;
import com.utc.blog.log.sdk.LogClient;
import com.utc.blog.log.sdk.LogEntry;
import com.utc.blog.log.sdk.LogLevel;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Appender Logback đẩy log của service lên admin. Service cứ dùng SLF4J như thường,
 * không phải gọi SDK trực tiếp.
 *
 * <pre>{@code
 * <appender name="ADMIN" class="com.utc.blog.log.sdk.logback.AdminLogAppender">
 *     <baseUrl>https://admin.example.com</baseUrl>
 *     <service>payment-service</service>
 *     <environment>production</environment>
 * </appender>
 * }</pre>
 *
 * Lỗi gửi log được báo qua status manager của Logback (addWarn), tuyệt đối không
 * ghi ngược vào SLF4J để tránh vòng lặp log.
 */
public class AdminLogAppender extends AppenderBase<ILoggingEvent> {

    private String baseUrl;
    private String service;
    private String environment;
    private String apiKey;

    private int batchSize = 50;
    private long flushIntervalMs = 1000;
    private int queueCapacity = 10_000;
    private int maxRetries = 1;
    private long connectTimeoutMs = 3000;
    private long requestTimeoutMs = 5000;

    private String traceIdKey = "traceId";
    private String userIdKey = "userId";
    private String actionKey = "action";
    private boolean includeMdc = true;
    private boolean includeThread = true;

    private LogClient client;

    @Override
    public void start() {
        if (baseUrl == null || baseUrl.trim().isEmpty()) {
            addError("AdminLogAppender thiếu <baseUrl>, appender sẽ không chạy");
            return;
        }
        if (service == null || service.trim().isEmpty()) {
            addWarn("AdminLogAppender chưa đặt <service>, log sẽ hiện là 'unknown' trên admin");
        }
        client = LogClient.builder(baseUrl)
                .service(service)
                .environment(environment)
                .apiKey(apiKey)
                .batchSize(batchSize)
                .flushInterval(Duration.ofMillis(flushIntervalMs))
                .queueCapacity(queueCapacity)
                .maxRetries(maxRetries)
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .requestTimeout(Duration.ofMillis(requestTimeoutMs))
                // báo qua status của Logback, không dùng SLF4J để khỏi log lồng log
                .onError(e -> addWarn("Không gửi được log lên admin: " + e.getMessage()))
                .shutdownHook(false)   // Logback tự gọi stop() khi tắt context
                .build();
        super.start();
    }

    @Override
    protected void append(ILoggingEvent event) {
        if (client == null) return;
        try {
            client.log(toEntry(event));
        } catch (Exception e) {
            // ghi log mà làm chết luồng nghiệp vụ thì hỏng, nuốt hết
            addWarn("Bỏ qua một log lỗi khi chuyển đổi: " + e.getMessage());
        }
    }

    @Override
    public void stop() {
        if (!isStarted()) return;
        super.stop();
        if (client != null) {
            client.close();   // đẩy nốt log còn trong hàng đợi
            client = null;
        }
    }

    private LogEntry toEntry(ILoggingEvent event) {
        Map<String, String> mdc = event.getMDCPropertyMap();
        LogEntry.Builder b = LogEntry.builder()
                .level(toLevel(event.getLevel()))
                .logger(event.getLoggerName())
                .message(event.getFormattedMessage())
                .loggedAt(LocalDateTime.ofInstant(Instant.ofEpochMilli(event.getTimeStamp()), ZoneId.systemDefault()));

        if (mdc != null && !mdc.isEmpty()) {
            b.traceId(mdc.get(traceIdKey));
            b.userId(mdc.get(userIdKey));
            b.action(mdc.get(actionKey));
        }

        IThrowableProxy throwable = event.getThrowableProxy();
        if (throwable != null) {
            b.stackTrace(ThrowableProxyUtil.asString(throwable));
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        if (includeThread && event.getThreadName() != null) {
            payload.put("thread", event.getThreadName());
        }
        // các key MDC còn lại gom vào payload để không mất ngữ cảnh
        if (includeMdc && mdc != null) {
            mdc.forEach((k, v) -> {
                if (!k.equals(traceIdKey) && !k.equals(userIdKey) && !k.equals(actionKey)) {
                    payload.put(k, v);
                }
            });
        }
        if (!payload.isEmpty()) {
            b.payload(payload);
        }
        return b.build();
    }

    private LogLevel toLevel(Level level) {
        if (level == null) return LogLevel.INFO;
        switch (level.toInt()) {
            case Level.TRACE_INT: return LogLevel.TRACE;
            case Level.DEBUG_INT: return LogLevel.DEBUG;
            case Level.WARN_INT:  return LogLevel.WARN;
            case Level.ERROR_INT: return LogLevel.ERROR;
            default:              return LogLevel.INFO;
        }
    }

    // ---------- setter cho logback.xml ----------

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public void setService(String service) {
        this.service = service;
    }

    public void setEnvironment(String environment) {
        this.environment = environment;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public void setFlushIntervalMs(long flushIntervalMs) {
        this.flushIntervalMs = flushIntervalMs;
    }

    public void setQueueCapacity(int queueCapacity) {
        this.queueCapacity = queueCapacity;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    public void setConnectTimeoutMs(long connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public void setRequestTimeoutMs(long requestTimeoutMs) {
        this.requestTimeoutMs = requestTimeoutMs;
    }

    /** Key MDC chứa traceId; mặc định "traceId". */
    public void setTraceIdKey(String traceIdKey) {
        this.traceIdKey = traceIdKey;
    }

    public void setUserIdKey(String userIdKey) {
        this.userIdKey = userIdKey;
    }

    public void setActionKey(String actionKey) {
        this.actionKey = actionKey;
    }

    /** Gom các key MDC còn lại vào payload; mặc định bật. */
    public void setIncludeMdc(boolean includeMdc) {
        this.includeMdc = includeMdc;
    }

    /** Đưa tên thread vào payload; mặc định bật. */
    public void setIncludeThread(boolean includeThread) {
        this.includeThread = includeThread;
    }

    /** Dùng cho test / cấu hình bằng code. */
    public LogClient getClient() {
        return client;
    }
}
