package com.github.lechandonga.requesttraceobservabilityjava.tests;

import com.github.lechandonga.requesttraceobservabilityjava.observability.metrics.MetricsRegistry;
import com.github.lechandonga.requesttraceobservabilityjava.observability.trace.MdcTaskDecorator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 观测层自保护：观测组件自身异常时必须降级放行，不影响业务请求。
 */
class ObservabilityDegradationIT {

    @Test
    @DisplayName("降级放行：指标记录内部异常被吞掉，注册表继续可用，业务无感知")
    void metricsRegistrySwallowsInternalFailures() {
        MetricsRegistry registry = new MetricsRegistry(1);

        assertDoesNotThrow(() -> {
            registry.record(null, null, -999L, false, null);
            registry.record("GET", "/a", 1L, true, "X");
            registry.record("GET", "/b", 1L, false, null);
        });

        assertTrue(registry.dimensionCount() <= 2,
                "维度上限生效，超出的维度归入兜底桶，内存有界");
        assertDoesNotThrow(registry::snapshot, "异常输入之后查询仍可正常工作");
    }

    @Test
    @DisplayName("降级放行：MDC 装饰器在 MDC 被异常清空等情况下仍执行业务任务")
    void mdcDecoratorAlwaysRunsBusinessTask() {
        MdcTaskDecorator decorator = new MdcTaskDecorator();
        final boolean[] ran = {false};

        MDC.clear();
        Runnable decorated = decorator.decorate(() -> ran[0] = true);
        assertDoesNotThrow(decorated::run);
        assertTrue(ran[0], "无论观测状态如何，业务任务必须照常执行");
    }
}
