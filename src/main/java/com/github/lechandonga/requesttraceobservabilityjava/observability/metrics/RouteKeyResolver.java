package com.github.lechandonga.requesttraceobservabilityjava.observability.metrics;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.servlet.HandlerMapping;

/**
 * 指标维度（method + 路由模板）解析。
 *
 * <p>必须按路由模板（如 {@code /users/{id}}）而不是原始地址统计，否则 id、query 参数等
 * 会让标签基数无限膨胀。匹配不到处理器（404）或模板缺失时归到兜底维度。
 */
public final class RouteKeyResolver {

    public static final String UNMATCHED_ROUTE = "unmatched";
    public static final String UNKNOWN_ROUTE = "unknown";

    private RouteKeyResolver() {
    }

    /**
     * 解析指标维度：优先使用路由模板；只有真正没有匹配到处理器（无模板）时才走兜底维度。
     *
     * <p>业务自身返回的 404（如 {@code /users/{id}} 查不到数据）仍归模板维度。
     */
    public static String resolve(HttpServletRequest request) {
        try {
            Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
            if (pattern instanceof String routeTemplate && !routeTemplate.isBlank()) {
                return normalize(routeTemplate);
            }
            return UNMATCHED_ROUTE;
        } catch (RuntimeException e) {
            return UNKNOWN_ROUTE;
        }
    }

    private static String normalize(String template) {
        String trimmed = template.trim();
        if (trimmed.length() > 128) {
            return UNKNOWN_ROUTE;
        }
        return trimmed;
    }
}
