# log-internal

SDK Java gửi log về admin nội bộ (`POST /api/log`). Không kéo theo dependency nào — chỉ dùng `java.net.http` của JDK, nên không đụng version Jackson/HttpClient của service tích hợp.

- Java 11+
- Bất đồng bộ, gom lô, không bao giờ ném exception ra luồng nghiệp vụ
- Admin chết thì service vẫn chạy bình thường
- Có sẵn **Logback appender**: service cứ log bằng SLF4J như thường, log tự bay lên admin

## Cài đặt

Lấy qua [JitPack](https://jitpack.io) — không cần token, không cần cài gì trước:

```xml
<repositories>
    <repository>
        <id>jitpack.io</id>
        <url>https://jitpack.io</url>
    </repository>
</repositories>

<dependency>
    <groupId>com.github.ToThangGTVT</groupId>
    <artifactId>log-internal</artifactId>
    <version>v1.0.0</version>
</dependency>
```

Gradle:

```groovy
repositories {
    mavenCentral()
    maven { url 'https://jitpack.io' }
}

dependencies {
    implementation 'com.github.ToThangGTVT:log-internal:v1.0.0'
}
```

Ghim theo tag (`v1.0.0`) chứ đừng dùng `main-SNAPSHOT` cho môi trường thật. Lần đầu một
version mới được yêu cầu, JitPack phải build nên có thể chờ 1-2 phút; các lần sau lấy từ cache.

Toàn bộ class nằm trong package `com.utc.blog.log.sdk` (appender ở `com.utc.blog.log.sdk.logback`):

```java
import com.utc.blog.log.sdk.LogClient;
import com.utc.blog.log.sdk.LogEntry;
import com.utc.blog.log.sdk.LogLevel;
```

## Cần xin gì từ bên admin

| Giá trị | Dùng vào | Ai cấp |
|---|---|---|
| `baseUrl` | gốc URL của admin, vd `https://admin.example.com` — **không kèm `/api/log`** | người vận hành admin |
| `apiKey` | khớp `app.log.api-key` bên admin, gửi qua header `X-Log-Api-Key` | người vận hành admin |

Admin không đặt `app.log.api-key` thì bỏ trống `apiKey` cũng chạy. Đặt rồi mà gửi sai
hoặc thiếu thì admin trả **401 và không ghi gì cả**.

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

`LogClient` an toàn đa luồng: tạo **một instance dùng chung cho cả app**, đừng `new` mỗi
lần ghi log (mỗi instance là một hàng đợi và một luồng nền riêng). Nhớ `close()` khi tắt app.

Spring Boot — `application.properties`:

```properties
spring.application.name=payment-service
admin.log.base-url=https://admin.example.com
admin.log.api-key=${LOG_API_KEY:}
```

```java
import com.utc.blog.log.sdk.LogClient;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class LogConfig {

    @Bean(destroyMethod = "close")   // Spring gọi close() -> flush nốt hàng đợi khi tắt app
    public LogClient logClient(@Value("${admin.log.base-url}") String baseUrl,
                               @Value("${admin.log.api-key:}") String apiKey,
                               @Value("${spring.application.name}") String service,
                               @Value("${spring.profiles.active:default}") String env) {
        return LogClient.builder(baseUrl)
                .service(service)
                .environment(env)
                .apiKey(apiKey)
                .onError(e -> LoggerFactory.getLogger(LogConfig.class)
                        .warn("Không gửi được log lên admin: {}", e.getMessage()))
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

### Bơm traceId cho mỗi request

Appender đọc `traceId` từ MDC, nhưng phải có ai đó đặt nó vào. Một filter là đủ:

```java
@Component
public class TraceIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String traceId = req.getHeader("X-Trace-Id");
        MDC.put("traceId", traceId != null ? traceId : UUID.randomUUID().toString());
        try {
            chain.doFilter(req, res);
        } finally {
            MDC.clear();   // bắt buộc: thread được tái sử dụng, không xoá là traceId lẫn sang request khác
        }
    }
}
```

Từ đó mọi log trong request mang cùng `traceId`, trên trang `/logs` lọc theo nó là thấy trọn
một luồng xử lý. Lưu ý MDC **không tự truyền sang thread khác** — code chạy trong
`@Async`/`CompletableFuture` phải tự set lại.

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

## Không thấy log lên admin thì kiểm gì

SDK cố tình im lặng khi gửi lỗi, nên việc đầu tiên là **mở đường báo lỗi ra**:

```java
LogClient.builder(baseUrl).service("payment-service")
        .onError(e -> e.printStackTrace())   // hoặc log ra console
        .build();
```

Với appender thì lỗi đi vào status của Logback, bật lên bằng:

```xml
<statusListener class="ch.qos.logback.core.status.OnConsoleStatusListener"/>
```

Sau đó đối chiếu:

| Hiện tượng | Nguyên nhân thường gặp |
|---|---|
| `HTTP 401` | thiếu/sai `apiKey`, không khớp `app.log.api-key` bên admin |
| `HTTP 404` | `baseUrl` bị kèm sẵn `/api/log`, thành `/api/log/api/log` |
| `ConnectException` / timeout | sai host/port, firewall, hoặc admin đang chết |
| Không lỗi gì nhưng admin vẫn trống | app tắt trước khi hàng đợi kịp đẩy — gọi `close()`, hoặc `flush(Duration.ofSeconds(5))` với job ngắn |
| Mất rải rác một số log | hàng đợi đầy, xem `client.dropped()`; tăng `queueCapacity` hoặc `batchSize` |
| Appender im hoàn toàn | thiếu `<baseUrl>`, xem status Logback; hoặc `ThresholdFilter` đang chặn mức log đó |

Kiểm nhanh admin còn sống không, không cần đụng vào code:

```bash
curl -i -X POST https://admin.example.com/api/log \
  -H 'Content-Type: application/json' \
  -H 'X-Log-Api-Key: <key>' \
  -d '{"service":"thu-tay","level":"INFO","message":"ping"}'
```

`202` là đạt.

## Đừng log dữ liệu nhạy cảm

Mọi thứ gửi đi đều **lưu vào DB của admin, ghi ra file trên máy admin, và hiện cho mọi
người đăng nhập được admin**. Với appender thì cả MDC cũng bay theo (`includeMdc` mặc định
bật). Nên tránh đặt token, mật khẩu, OTP, số thẻ, thông tin cá nhân vào message/MDC/payload.
Không kiểm soát được thì tắt: `<includeMdc>false</includeMdc>`.

## Test

```bash
./mvnw test
```

11 test dựng `HttpServer` của JDK giả làm admin:

- `LogClientTest` — đường dẫn, body JSON, header API key, gom lô, escape ký tự, thử lại khi 5xx, admin chết hẳn, chế độ đồng bộ, `forTrace`, drop khi hàng đợi đầy.
- `AdminLogAppenderTest` — log qua SLF4J lên tới admin kèm MDC/stack trace/payload, `stop()` flush hết, thiếu `baseUrl` thì không chạy chứ không ném lỗi.
