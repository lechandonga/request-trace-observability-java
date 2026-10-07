# 请求链路观测能力说明

本服务内置一套零外部依赖的请求链路观测能力，覆盖四个环节：

1. **关联标识（traceId）**：一次请求从进入到返回的所有日志、响应、指标都能用同一个 id 对上号；
2. **访问日志**：每个请求恰好一条结构化日志，失败只记一次并带失败原因；
3. **统一错误结构**：所有失败返回固定 JSON，稳定错误码区分不同失败原因；
4. **运行时指标接口**：按接口维度查询请求量、失败量和耗时分布（含分位数）。

所有观测组件均做了自保护：**观测层自己出问题时降级放行，不影响业务请求，也不把观测异常透给调用方。**

---

## 1. 关联标识（traceId）

### 1.1 传递规则

| 场景 | 行为 |
| --- | --- |
| 请求头携带 `X-Trace-Id` 且合法 | **原样沿用**，并在响应头 `X-Trace-Id` 中原样回写 |
| 未携带标识 | 自动生成 `trace-` + 32 位小写十六进制（16 字节随机数），如 `trace-4a466f3be7319585af367139ad1ba4e9` |
| 携带但格式非法 | 不沿用，自动生成新标识（非法值不会出现在响应中，避免日志注入/伪造） |

合法格式（`TraceIdGenerator`）：`^[a-z0-9_-]{8,64}$`，即 8~64 位小写字母、数字、`-`、`_`。
刻意只允许小写安全字符，避免在日志里注入换行、ANSI 等内容。

### 1.2 日志里怎么体现

标识放在 SLF4J MDC 的 `traceId` 键上，`logback-spring.xml` 的 pattern 统一输出：

```
%d{...} %-5level [%thread] [%X{traceId:-}] %logger{36} - %msg%n
```

业务代码无需手动透传，直接打日志即可带上标识，例如：

```
INFO  [http-nio-8080-exec-1] [my-trace-00001] DemoController - handling getUser id=42
```

### 1.3 异步线程池传播

- `TraceFilter` 在首次进入时把 MDC 快照存入 request attribute；
  Servlet 异步 `ASYNC` dispatch 回来时自动恢复，dispatch 结束清理，不污染 Tomcat 工作线程。
- 业务线程池通过 `MdcTaskDecorator` 传播：**提交任务时快照，执行任务时恢复，执行完还原工作线程原上下文**，
  因此线程被下一个请求复用时不会串号。
- 使用方式：注入名为 `requestTraceTaskExecutor` 的 `Executor`，或用 `@Async("requestTraceTaskExecutor")`。

```java
CompletableFuture.supplyAsync(() -> log.info("工作线程里仍带同一 traceId"), taskExecutor);
```

---

## 2. 访问日志与失败记录

### 2.1 统一格式

每个请求（含异步请求）由最内层的 `AccessLogFilter` **恰好输出一条** `ACCESS_LOG` logger 的结构化日志：

```
access SUCCESS method=GET route=/api/demo/users/{id} path=/api/demo/users/42 status=200 latencyMs=3 traceId=my-trace-00001
access FAILURE method=GET route=unmatched path=/no/such status=404 errorCode=NOT_FOUND reason="资源不存在" exception=...NoHandlerFoundException latencyMs=11 traceId=trace-404-0001
```

字段口径：

| 字段 | 说明 |
| --- | --- |
| `method` / `path` | HTTP 方法、**原始**请求路径，用于排查 |
| `route` | **路由模板**（指标同源），未匹配处理器时为 `unmatched`，观测自身异常时为 `unknown` |
| `status` | 最终 HTTP 状态码 |
| `errorCode` / `reason` / `exception` | 仅失败请求有；错误码稳定可聚合，reason 是可定位的原因，exception 是异常类名（不含堆栈） |
| `latencyMs` | 从进入过滤器到响应结束的耗时（毫秒），异步请求统计到异步处理完成 |
| `traceId` | 关联标识，与响应头、错误响应体一致 |

### 2.2 “同一失败只记一次”如何保证

- 异常由 `GlobalExceptionHandler` 统一处理，并把 `FailureMarker`（错误码/原因/异常类）写到 request attribute；
  4xx 打一条 WARN 摘要，5xx 由处理器记录**一次完整堆栈**。
- `AccessLogFilter` 用请求级 `AtomicBoolean` 保证整个生命周期（含 ASYNC dispatch）只做一次收口，
  不会在异常处理器和过滤器里各刷一遍。
- 过滤器同时是最后一道防线：漏网异常也会被兜底成稳定的 500 错误结构，仍然只记一次。

### 2.3 统一错误结构

所有失败都返回同一 JSON 结构：

```json
{
  "timestamp": "2026-10-07T20:03:39.639Z",
  "status": 404,
  "code": "NOT_FOUND",
  "message": "资源不存在",
  "path": "/no/such",
  "traceId": "trace-404-0001"
}
```

| code | HTTP 状态码 | 触发场景 |
| --- | --- | --- |
| `INVALID_REQUEST` | 400 | 参数校验失败、缺参、类型不匹配、请求体不可读 |
| `UNAUTHORIZED` | 401 | 未认证（预留） |
| `FORBIDDEN` | 403 | 禁止访问（预留） |
| `NOT_FOUND` | 404 | 无匹配处理器/路由，或业务主动判定资源不存在 |
| `METHOD_NOT_ALLOWED` | 405 | 请求方法不支持 |
| `REQUEST_REJECTED` | 422 | 可预期的业务拒绝（`BusinessException`） |
| `TOO_MANY_REQUESTS` | 429 | 限流（预留） |
| `INTERNAL_ERROR` | 500 | 非预期异常；对外只返回固定文案“服务器内部错误”，**不泄漏 message/堆栈** |

业务侧抛 `throw new BusinessException(ErrorCode.REQUEST_REJECTED, "余额不足")` 即可接入。

### 2.4 观测层自保护

`TraceFilter`、`AccessLogFilter`、`GlobalExceptionHandler`、`MetricsRegistry`、`MdcTaskDecorator`
内部的所有观测动作（MDC、日志、指标记录、错误体构造）均单独 try/catch：

- 失败时只在内部日志打一条观测故障记录，请求继续按业务逻辑执行；
- 业务异常永远原样透传给统一异常处理器，不会被观测组件吞掉或改写；
- 观测异常不会出现在对外响应里。

---

## 3. 指标接口

### 3.1 端点

`GET /observability/metrics`，返回进程启动至今的累计值：

```json
{
  "timestamp": "2026-10-08T...",
  "dimensionCount": 2,
  "routes": [
    {
      "method": "GET",
      "route": "/api/demo/users/{id}",
      "totalRequests": 4,
      "failedRequests": 1,
      "failureRatePercent": 25.0,
      "latency": {
        "sampleCount": 4,
        "minMs": 2, "maxMs": 53, "avgMs": 15.25,
        "p50Ms": 5, "p95Ms": 100, "p99Ms": 100,
        "bucketCounts": [0,1,1,0,1,0,1,0,0,0,0,0,0,0]
      },
      "failuresByCode": { "NOT_FOUND": 1 }
    }
  ]
}
```

### 3.2 维度口径与基数控制

- 维度 = **HTTP 方法 + 路由模板**（取自 Spring MVC 的 `BEST_MATCHING_PATTERN_ATTRIBUTE`）。
  `/users/1001` 与 `/users/1002` 都归 `/users/{id}`，**不按原始 URL、query 参数、path 原值打标签**。
- 真正没有匹配到处理器的请求归 `(method, unmatched)`（业务自身返回 404 的接口仍归它自己的模板）。
- 观测内部无法解析的情况归 `(method, unknown)`。
- 维度总数硬上限由 `observability.metrics.max-dimensions`（默认 200）控制；
  超限后新出现的维度一律计入 `(method, unknown)` 兜底桶，**内存不会随请求形态无限增长**。

### 3.3 并发正确性

- 请求量、失败量、错误码拆分、桶计数均用 `LongAdder`，最小/最大值用 `LongAccumulator`，
  高并发更新为无锁原子操作，不丢数、不错算（有 8 线程 × 250 次的并发测试保障）。
- 查询接口返回的是各计数器的瞬时快照。

### 3.4 分位数口径与限制

- 延迟直方图为**固定桶**，桶上界（毫秒）：
  `1, 2, 5, 10, 25, 50, 100, 250, 500, 1000, 2500, 5000, 10000`，另有一个 `(10000, +∞)` 桶。
- p50/p95/p99 为**近似值**：按桶计数累计定位到目标位次所在桶，返回该桶上界（保守上界近似）。
  例如样本都落在 `(2, 5]` 桶时，分位数报告为 `5`。
- 全部样本都在 `+∞` 桶时，分位数返回 `-1`（表示超过 10000ms，无法给出上界）。
- 指标为**单进程内存累计**：重启清零，多实例之间不聚合、不落盘。

---

## 4. 本地验证

### 4.1 自动化测试

```bash
mvn test
```

测试日志带 `TEST-RESULT` 前缀，可直接看到覆盖的行为（`@DisplayName` 中文描述）与每个用例结果，结尾有汇总：

```
TEST-RESULT =============== 测试结束：通过 29，失败 0，跳过 0 ===============
```

覆盖范围：

| 测试类 | 覆盖行为 |
| --- | --- |
| `TraceIdGeneratorTest` | 合法/非法标识边界（8 与 64 长度）、自动生成、MDC 快照恢复不串号 |
| `TraceIdLifecycleIT` | 正常链路：合法沿用、缺失生成、非法重生、响应头/响应体对号 |
| `AsyncTracePropagationIT` | 异步链路：traceId 进入 `biz-async-*` 工作线程、任务结束后 MDC 还原、并发不串号 |
| `ErrorHandlingIT` | 异常链路：404/400/405/422/500 的稳定结构、错误码区分、500 不泄漏内部细节、traceId 可对上 |
| `MetricsIT` | 路由模板聚合、`unmatched` 兜底、8×250 并发不丢数、失败按错误码拆分、分位数存在 |
| `ObservabilityDegradationIT` | 观测异常输入被吞掉、维度上限生效、装饰器始终执行业务任务 |

### 4.2 手动验证

```bash
mvn spring-boot:run

# 1) 调用方带合法标识：响应头原样取回
curl -i -H "X-Trace-Id: my-trace-00001" localhost:8080/api/demo/users/42

# 2) 不带 / 带非法标识：自动生成
curl -i localhost:8080/api/demo/ping
curl -i -H "X-Trace-Id: BAD!!" localhost:8080/api/demo/ping

# 3) 失败结构（分别看 code 的差异）
curl localhost:8080/no/such                    # 404 NOT_FOUND
curl localhost:8080/api/demo/boom             # 500 INTERNAL_ERROR（控制台堆栈只出现一次）
curl "localhost:8080/api/demo/reject?reason=x" # 422 REQUEST_REJECTED
curl -X POST -H "Content-Type: application/json" \
     -d '{"content":""}' localhost:8080/api/demo/echo   # 400 INVALID_REQUEST

# 4) 异步线程传播：响应 traceId 与控制台 biz-async-* 线程日志一致
curl -H "X-Trace-Id: async-0000001" localhost:8080/api/demo/async-ping

# 5) 指标：按模板聚合，含请求量/失败量/分位数
curl localhost:8080/observability/metrics
```

控制台可核对：每条业务日志与 `ACCESS_LOG` 都带相同 `[traceId]`；
异步请求的工作线程日志（`[biz-async-1]`）与请求线程是同一个标识；失败请求只有一条 FAILURE 访问日志。
