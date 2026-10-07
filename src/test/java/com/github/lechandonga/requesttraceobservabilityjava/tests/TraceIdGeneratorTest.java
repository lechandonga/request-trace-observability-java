package com.github.lechandonga.requesttraceobservabilityjava.tests;

import com.github.lechandonga.requesttraceobservabilityjava.observability.trace.TraceContext;
import com.github.lechandonga.requesttraceobservabilityjava.observability.trace.TraceIdGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 纯单元测试：关联标识的校验、生成与上下文快照/恢复。
 */
class TraceIdGeneratorTest {

    @ParameterizedTest
    @ValueSource(strings = {"abc12345", "trace-0123456789abcdef", "a-b_cdef",
            "0123456789012345678901234567890123456789012345678901234567890123"})
    @DisplayName("标识校验：合法格式（8~64 位小写字母数字-_）通过")
    void validIds(String id) {
        assertTrue(TraceIdGenerator.isValid(id));
        assertEquals(id, TraceIdGenerator.resolve(id), "合法标识必须原样沿用");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "short", "ABC12345", "bad id!!!!", "中文id0001",
            "01234567890123456789012345678901234567890123456789012345678901234"})
    @DisplayName("标识校验：空、过短、大写、特殊字符、超长等非法格式被拒绝")
    void invalidIds(String id) {
        assertFalse(TraceIdGenerator.isValid(id));
        assertNotEquals(id, TraceIdGenerator.resolve(id), "非法标识必须重新生成");
    }

    @Test
    @DisplayName("标识生成：缺失时自动生成，格式稳定且两次不重复")
    void generatesWhenAbsent() {
        String first = TraceIdGenerator.resolve(null);
        String second = TraceIdGenerator.resolve(null);
        assertTrue(first.matches("^trace-[0-9a-f]{32}$"));
        assertNotEquals(first, second);
    }

    @Test
    @DisplayName("上下文快照：可恢复到另一个线程，且还原后不污染调用方原上下文")
    void snapshotRestoreIsIsolated() {
        TraceContext.setTraceId("trace-snapshot-0001");
        Map<String, String> snapshot = TraceContext.snapshot();
        TraceContext.clear();

        Map<String, String> previous = TraceContext.restore(snapshot);
        assertEquals("trace-snapshot-0001", TraceContext.currentTraceId());
        TraceContext.restore(previous);
        assertNull(MDC.get("traceId"), "恢复旧上下文后不应残留快照内容");
    }
}
