package com.utc.blog.log.sdk;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Client gửi log về admin ({@code POST /api/log}).
 *
 * <pre>{@code
 * LogClient log = LogClient.builder("https://admin.example.com")
 *         .service("payment-service")
 *         .environment("production")
 *         .build();
 *
 * log.info("Tạo đơn thành công");
 * log.error("Gọi VNPay lỗi", ex);
 * }</pre>
 *
 * Mặc định chạy bất đồng bộ: log vào hàng đợi rồi một luồng nền gom lô gửi đi,
 * nên luồng nghiệp vụ không phải chờ mạng. Mọi lỗi gửi log đều bị nuốt
 * (bắt qua {@link Builder#onError}) để admin chết không kéo theo service.
 */
public final class LogClient implements AutoCloseable {

    private static final String PATH_SINGLE = "/api/log";
    private static final String PATH_BATCH = "/api/log/batch";

    private final String baseUrl;
    private final String service;
    private final String environment;
    private final String apiKey;
    private final Duration requestTimeout;
    private final int batchSize;
    private final long flushIntervalMs;
    private final int maxRetries;
    private final Consumer<Throwable> errorHandler;
    private final boolean async;

    private final HttpClient http;
    private final BlockingQueue<LogEntry> queue;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicLong dropped = new AtomicLong();
    private final Thread worker;
    private volatile boolean sending;
    private Thread shutdownHook;

    private LogClient(Builder b) {
        this.baseUrl = trimSlash(b.baseUrl);
        this.service = b.service;
        this.environment = b.environment;
        this.apiKey = b.apiKey;
        this.requestTimeout = b.requestTimeout;
        this.batchSize = b.batchSize;
        this.flushIntervalMs = b.flushInterval.toMillis();
        this.maxRetries = b.maxRetries;
        this.errorHandler = b.errorHandler;
        this.async = b.async;

        this.http = HttpClient.newBuilder()
                .connectTimeout(b.connectTimeout)
                .build();

        if (async) {
            this.queue = new ArrayBlockingQueue<>(b.queueCapacity);
            this.worker = new Thread(this::drainLoop, "log-sdk-sender");
            this.worker.setDaemon(true);
            this.worker.start();
            if (b.shutdownHook) {
                this.shutdownHook = new Thread(this::close, "log-sdk-shutdown");
                Runtime.getRuntime().addShutdownHook(this.shutdownHook);
            }
        } else {
            this.queue = null;
            this.worker = null;
        }
    }

    public static Builder builder(String baseUrl) {
        return new Builder(baseUrl);
    }

    // ---------- API tiện dụng ----------

    public void trace(String message) {
        log(LogLevel.TRACE, message);
    }

    public void debug(String message) {
        log(LogLevel.DEBUG, message);
    }

    public void info(String message) {
        log(LogLevel.INFO, message);
    }

    public void warn(String message) {
        log(LogLevel.WARN, message);
    }

    public void error(String message) {
        log(LogLevel.ERROR, message);
    }

    public void error(String message, Throwable error) {
        log(LogEntry.builder().level(LogLevel.ERROR).message(message).error(error).build());
    }

    public void fatal(String message, Throwable error) {
        log(LogEntry.builder().level(LogLevel.FATAL).message(message).error(error).build());
    }

    public void log(LogLevel level, String message) {
        log(LogEntry.builder().level(level).message(message).build());
    }

    /** Gửi log; bất đồng bộ nếu bật async (mặc định). */
    public void log(LogEntry entry) {
        if (entry == null) return;
        LogEntry filled = entry.withDefaults(service, environment);
        if (!async) {
            send(java.util.Collections.singletonList(filled));
            return;
        }
        if (!running.get()) return;
        // hàng đợi đầy thì bỏ log chứ không chặn luồng nghiệp vụ
        if (!queue.offer(filled)) {
            dropped.incrementAndGet();
        }
    }

    /** Gửi ngay tại luồng hiện tại, trả về true nếu admin nhận (2xx). */
    public boolean sendNow(LogEntry entry) {
        if (entry == null) return false;
        return send(java.util.Collections.singletonList(entry.withDefaults(service, environment)));
    }

    /** Tạo view gắn sẵn traceId, mọi log qua view này đều mang traceId đó. */
    public Scoped forTrace(String traceId) {
        return new Scoped(this, traceId);
    }

    /** Số log bị bỏ do hàng đợi đầy. */
    public long dropped() {
        return dropped.get();
    }

    /** Chờ hàng đợi trống; trả về false nếu hết thời gian mà vẫn còn log tồn. */
    public boolean flush(Duration timeout) {
        if (!async) return true;
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (queue.isEmpty() && !sending) return true;
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return queue.isEmpty() && !sending;
    }

    /** Đẩy nốt log còn trong hàng đợi rồi dừng luồng nền. */
    @Override
    public void close() {
        if (!running.compareAndSet(true, false)) return;
        if (worker != null) {
            try {
                worker.join(Math.max(2000, flushIntervalMs + requestTimeout.toMillis() * (maxRetries + 1)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        removeShutdownHook();
    }

    // ---------- Luồng nền ----------

    private void drainLoop() {
        // còn chạy hoặc còn log tồn thì tiếp tục gom lô để gửi
        while (running.get() || !queue.isEmpty()) {
            try {
                LogEntry first = queue.poll(Math.min(flushIntervalMs, 200), TimeUnit.MILLISECONDS);
                if (first == null) continue;
                List<LogEntry> batch = new ArrayList<>(batchSize);
                batch.add(first);
                queue.drainTo(batch, batchSize - 1);
                sending = true;
                try {
                    send(batch);
                } finally {
                    sending = false;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                sending = false;
                report(e);
            }
        }
    }

    // 1 log dùng /api/log, nhiều log dùng /api/log/batch
    private boolean send(List<LogEntry> batch) {
        if (batch == null || batch.isEmpty()) return true;
        boolean single = batch.size() == 1;
        String url = baseUrl + (single ? PATH_SINGLE : PATH_BATCH);
        String body = single
                ? batch.get(0).toJson()
                : Json.array(batch.stream().map(LogEntry::toJson).collect(Collectors.toList()));

        HttpRequest.Builder req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(requestTimeout)
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (apiKey != null && !apiKey.isEmpty()) {
            req.header("X-Log-Api-Key", apiKey);
        }
        HttpRequest request = req.build();

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                HttpResponse<Void> res = http.send(request, HttpResponse.BodyHandlers.discarding());
                int code = res.statusCode();
                if (code >= 200 && code < 300) return true;
                // 4xx là do body/API key sai, thử lại cũng vô ích
                if (code < 500) {
                    report(new IllegalStateException("Admin từ chối log, HTTP " + code));
                    return false;
                }
                report(new IllegalStateException("Admin lỗi, HTTP " + code));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            } catch (Exception e) {
                report(e);
            }
            if (attempt < maxRetries) sleep(200L * (attempt + 1));
        }
        return false;
    }

    private void report(Throwable e) {
        if (errorHandler == null) return;
        try {
            errorHandler.accept(e);
        } catch (Exception ignored) {
            // handler của người dùng lỗi thì cũng kệ, không được ảnh hưởng service
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void removeShutdownHook() {
        if (shutdownHook == null) return;
        try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
        } catch (IllegalStateException ignored) {
            // đang trong quá trình shutdown, không gỡ được thì thôi
        }
        shutdownHook = null;
    }

    private static String trimSlash(String url) {
        String v = url.trim();
        while (v.endsWith("/")) v = v.substring(0, v.length() - 1);
        return v;
    }

    /** View gắn sẵn traceId cho một request/job. */
    public static final class Scoped {
        private final LogClient client;
        private final String traceId;

        private Scoped(LogClient client, String traceId) {
            this.client = client;
            this.traceId = traceId;
        }

        public void info(String message) {
            log(LogLevel.INFO, message);
        }

        public void warn(String message) {
            log(LogLevel.WARN, message);
        }

        public void debug(String message) {
            log(LogLevel.DEBUG, message);
        }

        public void error(String message) {
            log(LogLevel.ERROR, message);
        }

        public void error(String message, Throwable error) {
            client.log(LogEntry.builder().level(LogLevel.ERROR).message(message).error(error)
                    .traceId(traceId).build());
        }

        public void log(LogLevel level, String message) {
            client.log(LogEntry.builder().level(level).message(message).traceId(traceId).build());
        }

        public void log(LogEntry entry) {
            client.log(entry.toBuilder().traceId(traceId).build());
        }
    }

    // ---------- Builder ----------

    public static final class Builder {
        private final String baseUrl;
        private String service;
        private String environment;
        private String apiKey;
        private Duration connectTimeout = Duration.ofSeconds(3);
        private Duration requestTimeout = Duration.ofSeconds(5);
        private int batchSize = 50;
        private Duration flushInterval = Duration.ofSeconds(1);
        private int queueCapacity = 10_000;
        private int maxRetries = 1;
        private Consumer<Throwable> errorHandler;
        private boolean async = true;
        private boolean shutdownHook = true;

        private Builder(String baseUrl) {
            if (baseUrl == null || baseUrl.trim().isEmpty()) {
                throw new IllegalArgumentException("baseUrl không được rỗng");
            }
            this.baseUrl = baseUrl;
        }

        /** Tên service hiện lên UI admin; nên đặt. */
        public Builder service(String service) {
            this.service = service;
            return this;
        }

        public Builder environment(String environment) {
            this.environment = environment;
            return this;
        }

        /** Khớp với app.log.api-key bên admin; bỏ qua nếu admin không bật. */
        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        public Builder connectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
            return this;
        }

        public Builder requestTimeout(Duration requestTimeout) {
            this.requestTimeout = requestTimeout;
            return this;
        }

        /** Số log tối đa gom trong một request batch. */
        public Builder batchSize(int batchSize) {
            this.batchSize = Math.max(1, batchSize);
            return this;
        }

        /** Chu kỳ luồng nền đẩy log đi. */
        public Builder flushInterval(Duration flushInterval) {
            this.flushInterval = flushInterval;
            return this;
        }

        /** Hàng đợi đầy thì log mới bị bỏ, đếm qua {@link LogClient#dropped()}. */
        public Builder queueCapacity(int queueCapacity) {
            this.queueCapacity = Math.max(1, queueCapacity);
            return this;
        }

        public Builder maxRetries(int maxRetries) {
            this.maxRetries = Math.max(0, maxRetries);
            return this;
        }

        /** Nơi nhận lỗi gửi log; mặc định im lặng hoàn toàn. */
        public Builder onError(Consumer<Throwable> errorHandler) {
            this.errorHandler = errorHandler;
            return this;
        }

        /** false = gửi đồng bộ ngay tại luồng gọi (job ngắn, CLI, test). */
        public Builder async(boolean async) {
            this.async = async;
            return this;
        }

        /** Tự flush khi JVM tắt; mặc định bật. */
        public Builder shutdownHook(boolean shutdownHook) {
            this.shutdownHook = shutdownHook;
            return this;
        }

        public LogClient build() {
            return new LogClient(this);
        }
    }
}
