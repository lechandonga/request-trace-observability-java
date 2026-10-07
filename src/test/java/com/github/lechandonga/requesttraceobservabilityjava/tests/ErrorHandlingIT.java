package com.github.lechandonga.requesttraceobservabilityjava.tests;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 异常链路：不同失败原因返回稳定结构、错误码可区分、traceId 可对上号。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ErrorHandlingIT {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    @Test
    @DisplayName("异常链路：404 返回稳定错误结构 NOT_FOUND，traceId 可对上号")
    void notFoundReturnsStableStructure() {
        ResponseEntity<Map> response = restTemplate.getForEntity(url("/no/such/path"), Map.class);

        assertEquals(404, response.getStatusCode().value());
        assertStableErrorBody(response, 404, "NOT_FOUND");
        assertEquals(response.getHeaders().getFirst("X-Trace-Id"), response.getBody().get("traceId"));
    }

    @Test
    @DisplayName("异常链路：400 参数非法返回 INVALID_REQUEST，错误结构稳定")
    void invalidRequestReturnsStableStructure() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> entity = new HttpEntity<>("{\"content\":\"\"}", headers);

        ResponseEntity<Map> response = restTemplate.exchange(url("/api/demo/echo"),
                HttpMethod.POST, entity, Map.class);

        assertEquals(400, response.getStatusCode().value());
        assertStableErrorBody(response, 400, "INVALID_REQUEST");
    }

    @Test
    @DisplayName("异常链路：422 业务拒绝返回 REQUEST_REJECTED，与其他失败原因可区分")
    void businessRejectionReturnsDistinctCode() {
        ResponseEntity<Map> response = restTemplate.getForEntity(
                url("/api/demo/reject?reason=余额不足"), Map.class);

        assertEquals(422, response.getStatusCode().value());
        assertStableErrorBody(response, 422, "REQUEST_REJECTED");
        assertEquals("余额不足", response.getBody().get("message"));
    }

    @Test
    @DisplayName("异常链路：404 业务异常与路由 404 使用同一稳定码，响应体仍区分路径")
    void businessNotFoundUsesSameStableCode() {
        ResponseEntity<Map> response = restTemplate.getForEntity(url("/api/demo/users/x-42"), Map.class);

        assertEquals(404, response.getStatusCode().value());
        assertStableErrorBody(response, 404, "NOT_FOUND");
    }

    @Test
    @DisplayName("异常链路：500 非预期异常返回 INTERNAL_ERROR，不泄漏内部异常细节，但 traceId 可用于定位")
    void unexpectedExceptionReturnsSafeInternalError() {
        ResponseEntity<Map> response = restTemplate.getForEntity(url("/api/demo/boom"), Map.class);

        assertEquals(500, response.getStatusCode().value());
        assertStableErrorBody(response, 500, "INTERNAL_ERROR");
        assertEquals("服务器内部错误", response.getBody().get("message"),
        "对外文案必须稳定，不能把异常 message/堆栈透给调用方");
        assertNotNull(response.getHeaders().getFirst("X-Trace-Id"));
    }

    @Test
    @DisplayName("异常链路：405 方法不支持返回 METHOD_NOT_ALLOWED")
    void methodNotAllowedReturnsDistinctCode() {
        ResponseEntity<Map> response = restTemplate.exchange(url("/api/demo/ping"),
                HttpMethod.POST, HttpEntity.EMPTY, Map.class);

        assertEquals(405, response.getStatusCode().value());
        assertStableErrorBody(response, 405, "METHOD_NOT_ALLOWED");
    }

    private void assertStableErrorBody(ResponseEntity<Map> response, int status, String code) {
        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertEquals(status, body.get("status"));
        assertEquals(code, body.get("code"));
        assertNotNull(body.get("message"));
        assertNotNull(body.get("path"));
        assertNotNull(body.get("timestamp"));
        assertNotNull(body.get("traceId"), "错误响应必须带 traceId 以便与日志对上号");
        assertTrue(((String) body.get("path")).startsWith("/"));
    }
}
