package com.github.lechandonga.requesttraceobservabilityjava.tests;

import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 测试结果日志监听器：在跑测试的日志中打印覆盖了哪些行为（测试类/用例名即行为描述）
 * 以及每个用例的结果，统一带 TEST-RESULT 前缀方便检索。
 */
public class TestResultLogger implements TestExecutionListener {

    private static final Logger log = LoggerFactory.getLogger("TEST_RESULT");

    private final AtomicInteger passed = new AtomicInteger();
    private final AtomicInteger failed = new AtomicInteger();
    private final AtomicInteger skipped = new AtomicInteger();

    @Override
    public void testPlanExecutionStarted(TestPlan testPlan) {
        log.info("TEST-RESULT =============== 测试开始，共 {} 个用例 ===============",
                testPlan.countTestIdentifiers(TestIdentifier::isTest));
    }

    @Override
    public void executionStarted(TestIdentifier testIdentifier) {
        if (testIdentifier.isTest()) {
            log.info("TEST-RESULT [开始] {} :: {}",
                    testIdentifier.getParentId().orElse(""), displayName(testIdentifier));
        }
    }

    @Override
    public void executionFinished(TestIdentifier testIdentifier, TestExecutionResult result) {
        if (!testIdentifier.isTest()) {
            return;
        }
        String name = displayName(testIdentifier);
        switch (result.getStatus()) {
            case SUCCESSFUL -> {
                passed.incrementAndGet();
                log.info("TEST-RESULT [通过] {}", name);
            }
            case FAILED -> {
                failed.incrementAndGet();
                String reason = result.getThrowable()
                        .map(Throwable::getMessage).orElse("unknown");
                log.error("TEST-RESULT [失败] {} -> {}", name, reason);
            }
            case ABORTED -> {
                skipped.incrementAndGet();
                log.warn("TEST-RESULT [中止] {}", name);
            }
        }
    }

    @Override
    public void executionSkipped(TestIdentifier testIdentifier, String reason) {
        if (testIdentifier.isTest()) {
            skipped.incrementAndGet();
            log.warn("TEST-RESULT [跳过] {} -> {}", displayName(testIdentifier), reason);
        }
    }

    @Override
    public void testPlanExecutionFinished(TestPlan testPlan) {
        log.info("TEST-RESULT =============== 测试结束：通过 {}，失败 {}，跳过 {} ===============",
                passed.get(), failed.get(), skipped.get());
    }

    private String displayName(TestIdentifier identifier) {
        return identifier.getLegacyReportingName().replace("()", "");
    }
}
