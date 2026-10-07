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
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 正常链路：关联标识的沿用、生成与响应回取。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TraceIdLifecycleIT {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    @Test
    @DisplayName("正常链路：调用方带合法 X-Trace-Id 时原样沿用，响应头与响应体可对上号")
    void validInboundTraceIdIsPreservedAndEchoedBack() {
        String inbound = "abc12345-trace_id";
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Trace-Id", inbound);

        ResponseEntity<Map> response = restTemplate.exchange(url("/api/demo/ping"),
                HttpMethod.GET, new HttpEntity<>(headers), Map.class);

        assertTrue(response.getStatusCode().is2xxSuccessful());
        org.junit.jupiter.api.Assertions.assertEquals(inbound,
                response.getHeaders().getFirst("X-Trace-Id"), "响应头必须原样返回入站标识");
        org.junit.jupiter.api.Assertions.assertEquals(inbound, response.getBody().get("traceId"),
                "处理过程中拿到的标识必须与入站一致");
    }

    @Test
    @DisplayName("正常链路：未携带标识时自动生成，格式为 trace-<32位hex>")
    void missingTraceIdIsGenerated() {
        ResponseEntity<Map> response = restTemplate.getForEntity(url("/api/demo/ping"), Map.class);

        String traceId = response.getHeaders().getFirst("X-Trace-Id");
        assertNotNull(traceId);
        assertTrue(traceId.matches("^trace-[0-9a-f]{32}$"), "自动生成的标识格式非法: " + traceId);
        org.junit.jupiter.api.Assertions.assertEquals(traceId, response.getBody().get("traceId"));
    }

    @Test
    @DisplayName("正常链路：携带非法标识（含大写/特殊字符/过短）时不沿用，自动生成新标识")
    void invalidInboundTraceIdIsRejectedAndRegenerated() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Trace-Id", "BAD ID!!!");

        ResponseEntity<Map> response = restTemplate.exchange(url("/api/demo/ping"),
                HttpMethod.GET, new HttpEntity<>(headers), Map.class);

        String traceId = response.getHeaders().getFirst("X-Trace-Id");
        assertNotNull(traceId);
        assertTrue(traceId.matches("^trace-[0-9a-f]{32}$"));
        org.junit.jupiter.api.Assertions.assertNotEquals("BAD ID!!!", traceId);
    }
}
