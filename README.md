# Rate-Limited API Gateway (Redis + Spring Boot)

A lightweight rate-limiting API gateway built with Java, Spring Boot, Spring Security, and Redis. The gateway intercepts incoming HTTP requests and enforces rate limits using custom, atomic Redis Lua scripts supporting both **Token Bucket** and **Sliding Window Log** algorithms.

---

## 1. Technology Stack

- **Java 21**: Core programming language.
- **Spring Boot 3.4.2**: Application framework.
- **Spring Web**: HTTP endpoints and web filter chain.
- **Spring Security**: Stateless API-key authentication.
- **Spring Data Redis (Lettuce)**: High-performance, reactive-capable Redis client for atomic Lua script execution.
- **Redis 7**: In-memory data store for rate-limiting state.
- **Docker Compose**: Containerization for Redis.
- **Maven**: Build tool and dependency manager.
- **JUnit 5 & Spring Boot Test**: Automated test suite.

---

## 2. Architecture & Request Flow

```
                      +---------------------------------------+
                      |             Client Request            |
                      +---------------------------------------+
                                          |
                                          v
                      +---------------------------------------+
                      |         Spring Security Filter        |
                      |   (Validates 'X-API-KEY' header)      |
                      +---------------------------------------+
                             |                         |
                    (Invalid / Missing)             (Valid)
                             |                         v
                             v         +-------------------------------+
                         HTTP 401      |        RateLimitFilter        |
                                       +-------------------------------+
                                                       |
                                                       v
                                       +-------------------------------+
                                       |       RateLimitService        |
                                       | (Identifies Identity: Key/User/IP)
                                       +-------------------------------+
                                                       |
                                                       v
                                       +-------------------------------+
                                       |      Atomic Redis Lua Script  |
                                       |  (Token Bucket / Sliding Win) |
                                       +-------------------------------+
                                            |                    |
                                       (Throttled)            (Allowed)
                                            |                    v
                                            v         +----------------+
                                        HTTP 429      | Controller     |
                                      Retry-After     | GET /api/test  |
                                                      +----------------+
                                                              |
                                                              v
                                                           HTTP 200
```

1. **Client Request**: The client sends an HTTP request with an `X-API-KEY` header to `/api/test`.
2. **Spring Security**: `ApiKeyAuthFilter` validates the key against configured credentials in `application.yml`. If missing or invalid, an HTTP 401 JSON error is returned immediately.
3. **Identity Resolution**: `RateLimitService` extracts the rate-limiting identity according to `rate-limit.identity-type` (`API_KEY`, `USER`, or client `IP`). Forwarded headers are ignored for IP limiting to prevent spoofing.
4. **Algorithm Decision**: The configured `RateLimiter` (`TokenBucketLimiter` or `SlidingWindowLimiter`) executes an atomic Redis Lua script.
5. **Enforcement**:
   - **Allowed**: The filter chain proceeds to `SampleController` and returns `200 OK`.
   - **Exceeded**: The filter halts execution, returning `429 Too Many Requests` with a valid non-negative integer `Retry-After` header and an error JSON body.

---

## 3. Rate-Limiting Algorithms

### A. Token Bucket Algorithm
- **Mechanism**: Each identity is allocated a bucket with a maximum capacity $C$ and replenished at a refill rate $R$ tokens per second.
- **State in Redis**: Stored in a Redis Hash at `rate_limit:token_bucket:{identity}` containing `tokens` (current balance) and `last_refill` (timestamp in ms).
- **Refill Logic**:
  $$\text{tokens} = \min(C, \text{tokens} + \Delta t \times R)$$
- **Decision**: If $\text{tokens} \ge 1$, one token is consumed and the request is allowed. Otherwise, the request is rejected with:
  $$\text{Retry-After} = \left\lceil \frac{1 - \text{tokens}}{R} \right\rceil$$
- **Atomicity**: The read, refill, balance check, consumption, and TTL update are executed atomically in a single Redis Lua script (`src/main/resources/scripts/token_bucket.lua`).

### B. Sliding Window Log Algorithm
- **Mechanism**: Maintains a precise log of request timestamps within a sliding time window $W$ (in seconds).
- **State in Redis**: Stored in a Redis Sorted Set (ZSET) at `rate_limit:sliding_window:{identity}`, where the score is the epoch millisecond timestamp and the value is a unique entry identifier.
- **Cleanup & Count**: Timestamps older than $t_{\text{now}} - W$ are purged via `ZREMRANGEBYSCORE`. Remaining items are counted via `ZCARD`.
- **Decision**: If count $< \text{maxRequests}$, the new request is recorded (`ZADD`) and allowed. If count $\ge \text{maxRequests}$, the request is rejected.
- **Retry Delay**: Computed from the expiration time of the oldest entry in the current window:
  $$\text{waitMs} = (t_{\text{oldest}} + W \times 1000) - t_{\text{now}}$$
  $$\text{Retry-After} = \max\left(1, \left\lceil \frac{\text{waitMs}}{1000} \right\rceil\right)$$
- **Atomicity**: Executed atomically in `src/main/resources/scripts/sliding_window.lua`.

---

## 4. Project Structure

```
rate-limited-api-gateway/
├── src/
│   ├── main/
│   │   ├── java/com/example/gateway/
│   │   │   ├── GatewayApplication.java
│   │   │   ├── config/
│   │   │   │   ├── RateLimitConfig.java
│   │   │   │   └── SecurityConfig.java
│   │   │   ├── controller/
│   │   │   │   └── SampleController.java
│   │   │   ├── filter/
│   │   │   │   └── RateLimitFilter.java
│   │   │   ├── limiter/
│   │   │   │   ├── RateLimiter.java
│   │   │   │   ├── RateLimitResult.java
│   │   │   │   ├── TokenBucketLimiter.java
│   │   │   │   └── SlidingWindowLimiter.java
│   │   │   └── service/
│   │   │       └── RateLimitService.java
│   │   └── resources/
│   │       ├── application.yml
│   │       └── scripts/
│   │           ├── token_bucket.lua
│   │           └── sliding_window.lua
│   └── test/
│       └── java/com/example/gateway/
│           ├── GatewayIntegrationTest.java
│           ├── RedisTestSupport.java
│           ├── TestClock.java
│           ├── config/
│           │   └── AlgorithmSelectionTest.java
│           ├── limiter/
│           │   ├── SlidingWindowLimiterTest.java
│           │   └── TokenBucketLimiterTest.java
│           └── service/
│               └── RateLimitServiceTest.java
├── compose.yaml
├── demo.py
├── demo.sh
├── pom.xml
└── README.md
```

---

## 5. Prerequisites

Before running the project manually, ensure you have the following installed:

| Tool | Minimum Version | Check Command | Purpose |
|------|-----------------|---------------|---------|
| **Java (JDK)** | 21+ | `java -version` | Application runtime & compilation |
| **Apache Maven** | 3.8+ | `mvn -version` | Build tool & dependency management |
| **Redis** | 7.x (or 6.2+) | `redis-cli --version` | In-memory distributed state store |
| **Docker & Compose** | Optional (recommended) | `docker compose version` | Quickest way to run Redis locally |
| **Python** | 3.8+ | `python --version` or `python3 --version` | Running the automated live demo script |
| **cURL** | Any modern version | `curl --version` | Sending manual HTTP test requests |

---

## 6. Step-by-Step Manual Run Guide

Follow these steps in order to start and run the gateway on your local machine:

### Step 1: Start Redis

The gateway requires a running Redis instance for atomic rate limiting. Choose either **Option A (Docker)** or **Option B (Native Redis)**:

#### Option A: Using Docker Compose (Recommended)
From the project root directory, run:
```bash
docker compose up -d
```
Verify the container is healthy and responding:
```bash
docker compose exec redis redis-cli ping
# Expected output: PONG
```

#### Option B: Using a Native/Local Redis Installation
If you prefer not to use Docker, start Redis directly on port `6379`:
- **macOS (Homebrew)**:
  ```bash
  brew install redis
  brew services start redis
  ```
- **Linux (Ubuntu / Debian)**:
  ```bash
  sudo apt update && sudo apt install -y redis-server
  sudo systemctl start redis-server
  ```
- **Windows (WSL2)**:
  ```bash
  wsl -d Ubuntu
  sudo service redis-server start
  ```
- **Verify local Redis connection**:
  ```bash
  redis-cli ping
  # Expected output: PONG
  ```

> [!NOTE]
> If your Redis server runs on a non-default host or port, you can customize it via environment variables:
> `export REDIS_HOST=localhost` and `export REDIS_PORT=6379`.

---

### Step 2: Build the Application

Navigate to the project root directory and build the project using Maven:

```bash
mvn clean package -DskipTests
```

This compiles all source files, validates dependencies, and packages the runnable Spring Boot JAR into `target/rate-limited-api-gateway-1.0.0.jar`.

---

### Step 3: Run the Application

You can launch the gateway using either the Maven plugin or the packaged executable JAR.

#### Method 1: Running with Maven (Best for Development)
```bash
mvn spring-boot:run
```

#### Method 2: Running with Executable JAR
```bash
java -jar target/rate-limited-api-gateway-1.0.0.jar
```

#### Method 3: Running with Custom CLI Arguments / Profiles
You can override any configuration property directly from the command line:

- **Switch to Sliding Window Log Algorithm**:
  ```bash
  java -jar target/rate-limited-api-gateway-1.0.0.jar --rate-limit.algorithm=SLIDING_WINDOW
  ```

- **Change HTTP Server Port (e.g. 8081)**:
  ```bash
  java -jar target/rate-limited-api-gateway-1.0.0.jar --server.port=8081
  ```

- **Connect to a Remote/Custom Redis Instance**:
  ```bash
  java -jar target/rate-limited-api-gateway-1.0.0.jar --spring.data.redis.host=192.168.1.50 --spring.data.redis.port=6379
  ```

Once started, the console will display:
```
Started GatewayApplication in X.XXX seconds (process running for ...)
Tomcat started on port 8080 (http) with context path '/'
```

---

### Step 4: Verify the Gateway is Working

Open a new terminal window to send requests to the running gateway.

#### 1. Valid API Key Request (Expect `200 OK`)
```bash
curl -i -H "X-API-KEY: test-api-key-1" http://localhost:8080/api/test
```
Response:
```http
HTTP/1.1 200 OK
Content-Type: application/json

{"status":"success","message":"Request processed successfully"}
```

#### 2. Missing API Key Request (Expect `401 Unauthorized`)
```bash
curl -i http://localhost:8080/api/test
```
Response:
```http
HTTP/1.1 401 Unauthorized
Content-Type: application/json;charset=UTF-8

{"error":"Unauthorized","message":"Missing or invalid API key"}
```

#### 3. Test Rate Limiting (Expect `429 Too Many Requests` after quota is exhausted)
By default, the rate limit allows 5 requests. Fire 6 or more rapid requests:
```bash
# Repeat this command 6 times:
curl -i -H "X-API-KEY: test-api-key-1" http://localhost:8080/api/test
```
On the 6th request within the window, the gateway throttles the client:
```http
HTTP/1.1 429 Too Many Requests
Retry-After: 9
Content-Type: application/json;charset=UTF-8

{"error":"Too Many Requests","message":"Rate limit exceeded. Try again later."}
```

---

## 7. Working on and Developing the Project

If you want to contribute, add features, or modify rate-limiting parameters, here is how to work with the codebase:

### 1. Opening in an IDE
- **IntelliJ IDEA**: Open the project folder or import `pom.xml`. Choose JDK 21 as the Project SDK.
- **VS Code**: Install the *Extension Pack for Java* and *Spring Boot Extension Pack*. Open the root folder.
- **Eclipse**: File -> Import -> Existing Maven Projects.

### 2. Configuration & Parameter Tuning
All rate limiter defaults and authentication credentials live in `src/main/resources/application.yml`:

```yaml
server:
  port: 8080

spring:
  data:
    redis:
      host: ${REDIS_HOST:127.0.0.1}
      port: ${REDIS_PORT:6379}

rate-limit:
  algorithm: TOKEN_BUCKET      # Options: TOKEN_BUCKET, SLIDING_WINDOW
  capacity: 5                  # Bucket capacity (Token Bucket)
  refill-rate: 1               # Tokens added per second (Token Bucket)
  window-seconds: 10           # Window duration in seconds (Sliding Window)
  max-requests: 5              # Max requests permitted in window (Sliding Window)
  identity-type: API_KEY       # Options: API_KEY, USER, IP
  api-keys:
    - key: test-api-key-1
      username: user1
    - key: test-api-key-2
      username: user2
    - key: test-api-key-3
      username: user3
```

### 3. Key Components to Explore
- **Filters**:
  - `src/main/java/com/example/gateway/config/SecurityConfig.java`: Configures stateless security chain and API key authentication filter.
  - `src/main/java/com/example/gateway/filter/RateLimitFilter.java`: Gateway interceptor checking quota and writing `429` / `Retry-After`.
- **Rate Limiters & Lua Scripts**:
  - `src/main/java/com/example/gateway/limiter/TokenBucketLimiter.java`: Implements Token Bucket via `src/main/resources/scripts/token_bucket.lua`.
  - `src/main/java/com/example/gateway/limiter/SlidingWindowLimiter.java`: Implements Sliding Window Log via `src/main/resources/scripts/sliding_window.lua`.
  - `src/main/java/com/example/gateway/service/RateLimitService.java`: Resolves client identity based on configured type (`API_KEY`, `USER`, or `IP`).

### 4. Inspecting & Resetting Redis State During Development
You can query Redis directly to see how keys and tokens are tracked:

- **List active rate limit keys**:
  ```bash
  redis-cli keys "rate_limit:*"
  ```
- **Inspect Token Bucket state**:
  ```bash
  redis-cli hgetall "rate_limit:token_bucket:api_key:test-api-key-1"
  # Output: tokens, last_refill timestamp
  ```
- **Inspect Sliding Window timestamps**:
  ```bash
  redis-cli zrange "rate_limit:sliding_window:api_key:test-api-key-1" 0 -1 WITHSCORES
  ```
- **Reset all rate limit data**:
  ```bash
  redis-cli flushdb
  ```
- **Monitor Redis operations in real time**:
  ```bash
  redis-cli monitor
  ```

---

## 8. Switching Algorithms

In `src/main/resources/application.yml`, set the `rate-limit.algorithm` property:

```yaml
rate-limit:
  algorithm: TOKEN_BUCKET      # Or: SLIDING_WINDOW
```

Alternatively, switch algorithms via CLI argument on launch:
```bash
java -jar target/rate-limited-api-gateway-1.0.0.jar --rate-limit.algorithm=SLIDING_WINDOW
```

---

## 9. API-Key Configuration & Example HTTP Requests

Configured API keys in `application.yml`:
- `test-api-key-1` (maps to `user1`)
- `test-api-key-2` (maps to `user2`)
- `test-api-key-3` (maps to `user3`)

### Example Requests

#### 1. Valid API Key (200 OK)
```bash
curl -i http://localhost:8080/api/test -H "X-API-KEY: test-api-key-1"
```
Response:
```http
HTTP/1.1 200 OK
Content-Type: application/json

{"status":"success","message":"Request processed successfully"}
```

#### 2. Missing API Key (401 Unauthorized)
```bash
curl -i http://localhost:8080/api/test
```
Response:
```http
HTTP/1.1 401 Unauthorized
Content-Type: application/json;charset=UTF-8

{"error":"Unauthorized","message":"Missing or invalid API key"}
```

#### 3. Invalid API Key (401 Unauthorized)
```bash
curl -i http://localhost:8080/api/test -H "X-API-KEY: invalid-key"
```
Response:
```http
HTTP/1.1 401 Unauthorized
Content-Type: application/json;charset=UTF-8

{"error":"Unauthorized","message":"Missing or invalid API key"}
```

#### 4. Rate Limit Exceeded (429 Too Many Requests)
```bash
curl -i http://localhost:8080/api/test -H "X-API-KEY: test-api-key-1"
```
Response:
```http
HTTP/1.1 429 Too Many Requests
Retry-After: 9
Content-Type: application/json;charset=UTF-8

{"error":"Too Many Requests","message":"Rate limit exceeded. Try again later."}
```

---

## 10. How to Run Automated Tests

Run the full JUnit 5 test suite with Maven:

```bash
mvn test
```

### Test Coverage Summary (17 Tests)
1. **Token bucket allows requests while tokens are available** (`TokenBucketLimiterTest`).
2. **Token bucket rejects requests when empty** (`TokenBucketLimiterTest`).
3. **Token bucket refills over time** (`TokenBucketLimiterTest` using `TestClock`).
4. **Sliding window allows requests below limit** (`SlidingWindowLimiterTest`).
5. **Sliding window rejects requests at limit** (`SlidingWindowLimiterTest`).
6. **Sliding window allows requests again after old entries expire** (`SlidingWindowLimiterTest` using `TestClock`).
7. **Different identities maintain independent limits** (`TokenBucketLimiterTest` and `SlidingWindowLimiterTest`).
8. **Gateway returns HTTP 429 when limit is exceeded** (`GatewayIntegrationTest`).
9. **Rejected requests contain Retry-After header** (`GatewayIntegrationTest`).
10. **Missing or invalid API keys receive HTTP 401** (`GatewayIntegrationTest`).
11. **Configured algorithm is selected correctly** (`AlgorithmSelectionTest`).
12. **Requests allowed by limiter reach the sample controller** (`GatewayIntegrationTest`).
13. **Identity resolution for API-Key, User, and IP** (`RateLimitServiceTest`).

---

## 11. How to Run the 20-Request Demo

The demonstration script uses only the Python standard library:

```bash
# 1. Reset Redis state for test key (optional):
# docker compose exec redis redis-cli del "rate_limit:token_bucket:api_key:test-api-key-1"

# 2. Run the 20-request demo:
python demo.py
```

### Demonstrating Token Bucket
```bash
# Start with TOKEN_BUCKET:
java -jar target/rate-limited-api-gateway-1.0.0.jar --rate-limit.algorithm=TOKEN_BUCKET
python demo.py
```

### Demonstrating Sliding Window
```bash
# Start with SLIDING_WINDOW:
java -jar target/rate-limited-api-gateway-1.0.0.jar --rate-limit.algorithm=SLIDING_WINDOW
python demo.py
```

---

## 12. Explanation of Retry-After Header

The `Retry-After` HTTP response header informs the client how many seconds to wait before retrying:
- **Token Bucket**: Calculated as the time required to replenish enough tokens to service a 1-token request:
  $$\text{Retry-After} = \left\lceil \frac{1 - \text{tokens}}{\text{refillRate}} \right\rceil$$
- **Sliding Window**: Calculated as the duration remaining until the oldest relevant timestamp in the sorted set leaves the sliding window:
  $$\text{Retry-After} = \left\lceil \frac{(t_{\text{oldest}} + W) - t_{\text{now}}}{1000} \right\rceil$$

Per RFC 7231, `Retry-After` is expressed as a non-negative integer number of seconds.

---

## 13. Troubleshooting & FAQs

### 1. `Connection refused: /127.0.0.1:6379`
- **Cause**: Redis is not running or is listening on another interface/port.
- **Fix**: Run `docker compose up -d` or verify local Redis status with `redis-cli ping`. If using a remote Redis, set `--spring.data.redis.host=<host>` and `--spring.data.redis.port=<port>`.

### 2. `Web server failed to start. Port 8080 was already in use.`
- **Cause**: Another service or previous instance of the gateway is occupying port 8080.
- **Fix**: Either stop the process using port 8080 (`netstat -ano | findstr :8080` on Windows, `lsof -i :8080` on macOS/Linux), or start the gateway on an alternate port:
  ```bash
  mvn spring-boot:run -Dspring-boot.run.arguments="--server.port=8081"
  # Or with JAR:
  java -jar target/rate-limited-api-gateway-1.0.0.jar --server.port=8081
  ```

### 3. `UnsupportedClassVersionError` or Compilation Errors
- **Cause**: The project requires Java 21+.
- **Fix**: Check your active Java version using `java -version` and `javac -version`. Ensure your `JAVA_HOME` environment variable points to a JDK 21+ installation.

### 4. `401 Unauthorized: Missing or invalid API key`
- **Cause**: The request omitted the `X-API-KEY` header, or provided a key that is not listed in `application.yml`.
- **Fix**: Send a valid configured header such as `-H "X-API-KEY: test-api-key-1"`.
