package com.utc.blog.log.sdk;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Chạy HttpServer của JDK giả làm admin để kiểm tra SDK gửi đúng đường dẫn, body và header.
 */
class LogClientTest {

    private HttpServer server;
    private String baseUrl;
    private final List<String> paths = new CopyOnWriteArrayList<>();
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private final List<String> apiKeys = new CopyOnWriteArrayList<>();
    private final AtomicInteger status = new AtomicInteger(202);

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/log", this::handle);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void handle(HttpExchange exchange) throws IOException {
        paths.add(exchange.getRequestURI().getPath());
        apiKeys.add(exchange.getRequestHeaders().getFirst("X-Log-Api-Key"));
        try (InputStream in = exchange.getRequestBody()) {
            bodies.add(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        exchange.sendResponseHeaders(status.get(), -1);
        exchange.close();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void guiMotLogDungDuongDanVaBody() {
        try (LogClient client = LogClient.builder(baseUrl + "/")   // thừa dấu / vẫn phải chạy
                .service("payment-service")
                .environment("production")
                .apiKey("secret-key")
                .flushInterval(Duration.ofMillis(50))
                .build()) {
            client.error("Timeout khi gọi VNPay");
            assertTrue(client.flush(Duration.ofSeconds(5)), "phải gửi hết log trong 5s");
        }

        assertEquals(Collections.singletonList("/api/log"), paths);
        String body = bodies.get(0);
        assertTrue(body.contains("\"service\":\"payment-service\""), body);
        assertTrue(body.contains("\"environment\":\"production\""), body);
        assertTrue(body.contains("\"level\":\"ERROR\""), body);
        assertTrue(body.contains("\"message\":\"Timeout khi gọi VNPay\""), body);
        assertTrue(body.contains("\"loggedAt\":\""), body);
        assertEquals("secret-key", apiKeys.get(0));
    }

    @Test
    void gomNhieuLogThanhMotRequestBatch() {
        try (LogClient client = LogClient.builder(baseUrl)
                .service("etl-worker")
                .flushInterval(Duration.ofMillis(200))
                .batchSize(50)
                .build()) {
            for (int i = 0; i < 10; i++) {
                client.info("dòng " + i);
            }
            assertTrue(client.flush(Duration.ofSeconds(5)));
        }

        assertTrue(paths.contains("/api/log/batch"), "phải dùng endpoint batch: " + paths);
        String all = String.join("", bodies);
        assertTrue(all.startsWith("["), all);
        for (int i = 0; i < 10; i++) {
            assertTrue(all.contains("\"message\":\"dòng " + i + "\""), "thiếu dòng " + i);
        }
        assertNull(apiKeys.get(0), "không cấu hình apiKey thì không gửi header");
    }

    @Test
    void escapeKyTuDacBietVaGiuStackTrace() {
        try (LogClient client = LogClient.builder(baseUrl).service("crm").build()) {
            client.error("Lỗi \"nặng\"\nxuống dòng\ttab", new IllegalStateException("bể rồi"));
            assertTrue(client.flush(Duration.ofSeconds(5)));
        }

        String body = bodies.get(0);
        assertTrue(body.contains("\\\"nặng\\\""), body);
        assertTrue(body.contains("\\n"), body);
        assertTrue(body.contains("\\t"), body);
        assertTrue(body.contains("java.lang.IllegalStateException: bể rồi"), body);
    }

    @Test
    void adminLoi5xxThiThuLaiVaKhongNemException() {
        status.set(500);
        List<Throwable> errors = new CopyOnWriteArrayList<>();

        try (LogClient client = LogClient.builder(baseUrl)
                .service("crm")
                .maxRetries(2)
                .requestTimeout(Duration.ofSeconds(2))
                .onError(errors::add)
                .build()) {
            client.info("thử lại đi");
            client.flush(Duration.ofSeconds(10));
        }

        assertEquals(3, paths.size(), "1 lần đầu + 2 lần thử lại");
        assertEquals(3, errors.size());
    }

    @Test
    void adminChetHanThiServiceVanChayBinhThuong() {
        server.stop(0);
        List<Throwable> errors = new CopyOnWriteArrayList<>();

        try (LogClient client = LogClient.builder(baseUrl)
                .service("crm")
                .maxRetries(0)
                .connectTimeout(Duration.ofMillis(300))
                .requestTimeout(Duration.ofMillis(500))
                .onError(errors::add)
                .build()) {
            assertDoesNotThrow(() -> client.info("không ai nhận"));
            client.flush(Duration.ofSeconds(5));
        }

        assertFalse(errors.isEmpty(), "lỗi phải được đẩy vào onError chứ không ném ra ngoài");
    }

    @Test
    void cheDoDongBoTraVeKetQuaGui() {
        try (LogClient client = LogClient.builder(baseUrl)
                .service("cli-job")
                .async(false)
                .build()) {
            boolean ok = client.sendNow(LogEntry.builder()
                    .level(LogLevel.WARN)
                    .message("chạy tay")
                    .traceId("job-1")
                    .payload(new java.util.LinkedHashMap<String, Object>() {{
                        put("rows", 120);
                        put("dryRun", true);
                    }})
                    .build());
            assertTrue(ok);
        }

        String body = bodies.get(0);
        assertTrue(body.contains("\"traceId\":\"job-1\""), body);
        assertTrue(body.contains("\"payload\":\"{\\\"rows\\\":120,\\\"dryRun\\\":true}\""), body);
    }

    @Test
    void gaTraceIdChoMoiLogTrongScope() {
        try (LogClient client = LogClient.builder(baseUrl).service("api-gateway").build()) {
            LogClient.Scoped scoped = client.forTrace("req-99");
            scoped.info("nhận request");
            scoped.log(LogEntry.builder().level(LogLevel.INFO)
                    .message("trả response").http("POST", "/v1/orders", 201, 42L).build());
            assertTrue(client.flush(Duration.ofSeconds(5)));
        }

        String all = String.join("", bodies);
        int count = 0;
        for (String part : new ArrayList<>(bodies)) {
            count += part.split("\"traceId\":\"req-99\"", -1).length - 1;
        }
        assertEquals(2, count, all);
        assertTrue(all.contains("\"uri\":\"/v1/orders\""), all);
        assertTrue(all.contains("\"statusCode\":201"), all);
    }

    @Test
    void hangDoiDayThiBoLogChuKhongChanLuongGoi() throws Exception {
        server.stop(0);   // không ai nhận -> log nằm lại trong hàng đợi
        try (LogClient client = LogClient.builder(baseUrl)
                .service("noisy")
                .queueCapacity(5)
                .connectTimeout(Duration.ofMillis(200))
                .requestTimeout(Duration.ofMillis(300))
                .build()) {
            for (int i = 0; i < 500; i++) {
                client.info("spam " + i);
            }
            assertTrue(client.dropped() > 0, "phải có log bị bỏ khi hàng đợi đầy");
        }
    }
}
