package com.github.lechandonga.requesttraceobservabilityjava.observability.config;

import com.github.lechandonga.requesttraceobservabilityjava.observability.trace.MdcTaskDecorator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 装配带 MDC 传播能力的异步线程池。业务代码注入名为 {@code requestTraceTaskExecutor}
 * 的 {@link Executor} 即可在线程切换后继续沿用同一 traceId。
 */
@Configuration
public class AsyncConfig {

    @Bean(name = "requestTraceTaskExecutor")
    @ConditionalOnMissingBean(name = "requestTraceTaskExecutor")
    public TaskExecutor requestTraceTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("biz-async-");
        executor.setTaskDecorator(new MdcTaskDecorator());
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
