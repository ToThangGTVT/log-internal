package com.utc.blog.log.sdk.logback;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Service dùng SLF4J như bình thường, appender phải tự đẩy log lên admin.
 */
class AdminLogAppenderTest {

    private HttpServer server;
    private String baseUrl;
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private CountDownLatch received;

    @BeforeEach
    void startServer() throws IOException {
        received = new CountDownLatch(1);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/log", this::handle);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            bodies.add(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        exchange.sendResponseHeaders(202, -1);
        exchange.close();
        received.countDown();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
        MDC.clear();
    }

    private AdminLogAppender attach(Logger logger) {
        AdminLogAppender appender = new AdminLogAppender();
        appender.setContext((LoggerContext) LoggerFactory.getILoggerFactory());
        appender.setBaseUrl(baseUrl);
        appender.setService("payment-service");
        appender.setEnvironment("production");
        appender.setFlushIntervalMs(50);
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    @Test
    void logQuaSlf4jThiDayLenAdmin() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger("com.example.OrderService");
        AdminLogAppender appender = attach(logger);

        MDC.put("traceId", "req-99");
        MDC.put("userId", "42");
        MDC.put("orderId", "9931");
        logger.error("Timeout khi gọi VNPay", new IllegalStateException("bể rồi"));
        MDC.clear();

        assertTrue(received.await(5, TimeUnit.SECONDS), "admin phải nhận được log");
        appender.stop();
        logger.detachAppender(appender);

        String body = String.join("", bodies);
        assertTrue(body.contains("\"service\":\"payment-service\""), body);
        assertTrue(body.contains("\"environment\":\"production\""), body);
        assertTrue(body.contains("\"level\":\"ERROR\""), body);
        assertTrue(body.contains("\"logger\":\"com.example.OrderService\""), body);
        assertTrue(body.contains("\"message\":\"Timeout khi gọi VNPay\""), body);
        assertTrue(body.contains("\"traceId\":\"req-99\""), body);
        assertTrue(body.contains("\"userId\":\"42\""), body);
        assertTrue(body.contains("java.lang.IllegalStateException: bể rồi"), body);
        // key MDC còn lại và tên thread gom vào payload
        assertTrue(body.contains("\\\"orderId\\\":\\\"9931\\\""), body);
        assertTrue(body.contains("\\\"thread\\\":"), body);
    }

    @Test
    void stopPhaiDayNotLogConTonTrongHangDoi() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger("com.example.Slow");
        AdminLogAppender appender = attach(logger);
        appender.getClient();   // client đã dựng xong

        logger.info("log cuối trước khi tắt app");
        appender.stop();        // stop() phải chờ gửi hết
        logger.detachAppender(appender);

        assertTrue(bodies.stream().anyMatch(b -> b.contains("log cuối trước khi tắt app")),
                "stop() phải flush hết hàng đợi, đã nhận: " + bodies);
    }

    @Test
    void thieuBaseUrlThiKhongChayChuKhongNemLoi() {
        AdminLogAppender appender = new AdminLogAppender();
        appender.setContext((LoggerContext) LoggerFactory.getILoggerFactory());
        assertDoesNotThrow(appender::start);
        assertFalse(appender.isStarted());
        assertNull(appender.getClient());
    }
}
