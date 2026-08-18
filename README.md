# log-internal

SDK Java gửi log về admin nội bộ (`POST /api/log`). Không kéo theo dependency nào — chỉ dùng `java.net.http` của JDK, nên không đụng version Jackson/HttpClient của service tích hợp.

- Java 11+
- Bất đồng bộ, gom lô, không bao giờ ném exception ra luồng nghiệp vụ
- Admin chết thì service vẫn chạy bình thường
- Có sẵn **Logback appender**: service cứ log bằng SLF4J như thường, log tự bay lên admin

## Cài đặt

```bash
mvn install
```

```xml
<dependency>
    <groupId>com.utc.blog</groupId>
    <artifactId>log-sdk</artifactId>
    <version>1.0.0</version>
</dependency>
```

## Dùng

```java
LogClient log = LogClient.builder("https://admin.example.com")   // base url tự truyền
        .service("payment-service")
        .environment("production")
        .build();

log.info("Tạo đơn thành công");
log.warn("Retry lần 2");
log.error("Gọi VNPay lỗi", ex);
```

Log đầy đủ field:

```java
log.log(LogEntry.builder()
        .level(LogLevel.ERROR)
        .message("Timeout khi gọi VNPay")
        .logger(OrderService.class)
        .action("createOrder")
        .traceId(traceId)
        .userId("42")
        .http("POST", "/v1/orders", 504, 3021L)
        .payload(Map.of("orderId", 9931))
        .error(ex)
        .build());
```

Gắn sẵn `traceId` cho cả một request/job:

```java
LogClient.Scoped scoped = log.forTrace(requestId);
scoped.info("nhận request");
scoped.error("xử lý lỗi", ex);
```

Spring Boot:

```java
@Configuration
public class LogConfig {

    @Bean(destroyMethod = "close")
    public LogClient logClient(@Value("${admin.log.base-url}") String baseUrl,
                               @Value("${admin.log.api-key:}") String apiKey,
                               @Value("${spring.application.name}") String service) {
        return LogClient.builder(baseUrl)
                .service(service)
                .environment("production")
                .apiKey(apiKey)
                .onError(e -> LoggerFactory.getLogger(LogConfig.class)
                        .debug("Không gửi được log lên admin: {}", e.getMessage()))
                .build();
    }
}
```

## Logback appender

Không muốn gọi SDK trong code nghiệp vụ thì gắn appender, mọi log qua SLF4J sẽ tự lên admin.

`logback.xml` (hoặc `logback-spring.xml` với Spring Boot):

```xml
<configuration>
    <appender name="ADMIN" class="com.utc.blog.log.sdk.logback.AdminLogAppender">
        <baseUrl>https://admin.example.com</baseUrl>
        <service>payment-service</service>
        <environment>production</environment>
        <!-- <apiKey>...</apiKey> nếu admin bật app.log.api-key -->

        <!-- chỉ đẩy WARN trở lên cho đỡ ồn, log thường vẫn ra console -->
        <filter class="ch.qos.logback.classic.filter.ThresholdFilter">
            <level>WARN</level>
        </filter>
    </appender>

    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder><pattern>%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n</pattern></encoder>
    </appender>

    <root level="INFO">
        <appender-ref ref="CONSOLE"/>
        <appender-ref ref="ADMIN"/>
    </root>
</configuration>
```

Từ đó trong code chỉ cần:

```java
private static final Logger log = LoggerFactory.getLogger(OrderService.class);

MDC.put("traceId", requestId);
log.error("Timeout khi gọi VNPay", ex);   // tự lên admin kèm traceId + stack trace
```

Appender lấy từ Logback: level, tên logger, message đã format, stack trace, thời điểm sinh log. Từ MDC: `traceId`, `userId`, `action` (đổi tên key được), các key MDC còn lại cùng tên thread gom vào `payload`.

Thẻ cấu hình: `baseUrl` (bắt buộc), `service`, `environment`, `apiKey`, `batchSize`, `flushIntervalMs`, `queueCapacity`, `maxRetries`, `connectTimeoutMs`, `requestTimeoutMs`, `traceIdKey`, `userIdKey`, `actionKey`, `includeMdc`, `includeThread`.

Vài điểm đã tính sẵn:

- Thiếu `baseUrl` thì appender báo lỗi qua status của Logback rồi nằm im, **không làm chết app**.
- Lỗi gửi log báo qua `addWarn` chứ không ghi ngược vào SLF4J, nên không có vòng lặp log đẻ ra log.
- `stop()` (Logback gọi khi tắt context) sẽ flush hết hàng đợi rồi mới dừng.
- `logback-classic` khai scope `provided`, không kéo theo service nào không dùng Logback.

## Cấu hình

| Builder | Mặc định | Ý nghĩa |
|---|---|---|
| `service(...)` | — | tên service hiện trên UI admin, nên đặt |
| `environment(...)` | — | `dev` / `staging` / `production` |
| `apiKey(...)` | — | khớp `app.log.api-key` bên admin, gửi qua header `X-Log-Api-Key` |
| `connectTimeout` / `requestTimeout` | 3s / 5s | timeout mạng |
| `batchSize` | 50 | số log tối đa gom vào một request `/api/log/batch` |
| `flushInterval` | 1s | chu kỳ luồng nền đẩy log đi |
| `queueCapacity` | 10.000 | hàng đợi đầy thì bỏ log mới, đếm qua `dropped()` |
| `maxRetries` | 1 | số lần thử lại khi admin lỗi 5xx / mất mạng |
| `onError(...)` | im lặng | nơi nhận lỗi gửi log |
| `async(false)` | async | gửi thẳng tại luồng gọi, hợp với CLI/job ngắn |
| `shutdownHook(false)` | bật | tắt việc tự flush khi JVM dừng |

`flush(Duration)` chờ đẩy hết hàng đợi, `close()` flush rồi dừng luồng nền (`LogClient` là `AutoCloseable`).

Không cần khai `host` — admin tự lấy IP người gọi.

## Test

```bash
mvn test
```

11 test dựng `HttpServer` của JDK giả làm admin:

- `LogClientTest` — đường dẫn, body JSON, header API key, gom lô, escape ký tự, thử lại khi 5xx, admin chết hẳn, chế độ đồng bộ, `forTrace`, drop khi hàng đợi đầy.
- `AdminLogAppenderTest` — log qua SLF4J lên tới admin kèm MDC/stack trace/payload, `stop()` flush hết, thiếu `baseUrl` thì không chạy chứ không ném lỗi.
