package com.utc.blog.log.sdk;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Một dòng log gửi lên admin. Dùng {@link #builder()} để tạo.
 * Chỉ 'message' là bắt buộc; 'service'/'environment' bỏ trống thì LogClient tự điền.
 */
public final class LogEntry {

    private final String service;
    private final String environment;
    private final LogLevel level;
    private final String logger;
    private final String message;
    private final String traceId;
    private final String userId;
    private final String action;
    private final String httpMethod;
    private final String uri;
    private final Integer statusCode;
    private final Long durationMs;
    private final String host;
    private final String tags;
    private final String payload;
    private final String stackTrace;
    private final LocalDateTime loggedAt;

    private LogEntry(Builder b) {
        this.service = b.service;
        this.environment = b.environment;
        this.level = b.level;
        this.logger = b.logger;
        this.message = b.message;
        this.traceId = b.traceId;
        this.userId = b.userId;
        this.action = b.action;
        this.httpMethod = b.httpMethod;
        this.uri = b.uri;
        this.statusCode = b.statusCode;
        this.durationMs = b.durationMs;
        this.host = b.host;
        this.tags = b.tags;
        this.payload = b.payload;
        this.stackTrace = b.stackTrace;
        this.loggedAt = b.loggedAt;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getMessage() {
        return message;
    }

    public LogLevel getLevel() {
        return level;
    }

    // điền giá trị mặc định của client vào những field entry chưa khai
    LogEntry withDefaults(String defaultService, String defaultEnvironment) {
        Builder b = toBuilder();
        if (b.service == null) b.service = defaultService;
        if (b.environment == null) b.environment = defaultEnvironment;
        if (b.level == null) b.level = LogLevel.INFO;
        if (b.loggedAt == null) b.loggedAt = LocalDateTime.now();
        return b.build();
    }

    String toJson() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("service", service);
        map.put("environment", environment);
        map.put("level", level == null ? null : level.name());
        map.put("logger", logger);
        map.put("message", message);
        map.put("traceId", traceId);
        map.put("userId", userId);
        map.put("action", action);
        map.put("httpMethod", httpMethod);
        map.put("uri", uri);
        map.put("statusCode", statusCode);
        map.put("durationMs", durationMs);
        map.put("host", host);
        map.put("tags", tags);
        map.put("payload", payload);
        map.put("stackTrace", stackTrace);
        map.put("loggedAt", loggedAt == null ? null : loggedAt.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        return Json.object(map);
    }

    Builder toBuilder() {
        Builder b = new Builder();
        b.service = service;
        b.environment = environment;
        b.level = level;
        b.logger = logger;
        b.message = message;
        b.traceId = traceId;
        b.userId = userId;
        b.action = action;
        b.httpMethod = httpMethod;
        b.uri = uri;
        b.statusCode = statusCode;
        b.durationMs = durationMs;
        b.host = host;
        b.tags = tags;
        b.payload = payload;
        b.stackTrace = stackTrace;
        b.loggedAt = loggedAt;
        return b;
    }

    public static final class Builder {
        private String service;
        private String environment;
        private LogLevel level;
        private String logger;
        private String message;
        private String traceId;
        private String userId;
        private String action;
        private String httpMethod;
        private String uri;
        private Integer statusCode;
        private Long durationMs;
        private String host;
        private String tags;
        private String payload;
        private String stackTrace;
        private LocalDateTime loggedAt;

        public Builder service(String service) {
            this.service = service;
            return this;
        }

        public Builder environment(String environment) {
            this.environment = environment;
            return this;
        }

        public Builder level(LogLevel level) {
            this.level = level;
            return this;
        }

        public Builder logger(String logger) {
            this.logger = logger;
            return this;
        }

        public Builder logger(Class<?> type) {
            this.logger = type == null ? null : type.getName();
            return this;
        }

        public Builder message(String message) {
            this.message = message;
            return this;
        }

        public Builder traceId(String traceId) {
            this.traceId = traceId;
            return this;
        }

        public Builder userId(String userId) {
            this.userId = userId;
            return this;
        }

        public Builder action(String action) {
            this.action = action;
            return this;
        }

        /** Log kiểu request: method, uri, status, thời gian xử lý. */
        public Builder http(String method, String uri, Integer statusCode, Long durationMs) {
            this.httpMethod = method;
            this.uri = uri;
            this.statusCode = statusCode;
            this.durationMs = durationMs;
            return this;
        }

        public Builder httpMethod(String httpMethod) {
            this.httpMethod = httpMethod;
            return this;
        }

        public Builder uri(String uri) {
            this.uri = uri;
            return this;
        }

        public Builder statusCode(Integer statusCode) {
            this.statusCode = statusCode;
            return this;
        }

        public Builder durationMs(Long durationMs) {
            this.durationMs = durationMs;
            return this;
        }

        /** Bỏ trống thì admin tự lấy IP người gọi. */
        public Builder host(String host) {
            this.host = host;
            return this;
        }

        public Builder tags(String tags) {
            this.tags = tags;
            return this;
        }

        /** Payload là chuỗi JSON có sẵn. */
        public Builder payload(String payloadJson) {
            this.payload = payloadJson;
            return this;
        }

        /** Payload dạng map, SDK tự encode sang JSON. */
        public Builder payload(Map<String, ?> payload) {
            this.payload = payload == null ? null : Json.object(new LinkedHashMap<>(payload));
            return this;
        }

        public Builder stackTrace(String stackTrace) {
            this.stackTrace = stackTrace;
            return this;
        }

        /** Lấy nguyên stack trace của exception; message trống thì lấy luôn toString() của lỗi. */
        public Builder error(Throwable error) {
            if (error == null) return this;
            StringWriter sw = new StringWriter();
            error.printStackTrace(new PrintWriter(sw));
            this.stackTrace = sw.toString();
            if (this.message == null) this.message = error.toString();
            return this;
        }

        /** Thời điểm sinh log; bỏ trống thì lấy lúc gửi. */
        public Builder loggedAt(LocalDateTime loggedAt) {
            this.loggedAt = loggedAt;
            return this;
        }

        public LogEntry build() {
            return new LogEntry(this);
        }
    }
}
