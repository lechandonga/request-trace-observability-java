package com.github.lechandonga.requesttraceobservabilityjava.observability.trace;

import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * 关联标识的校验与生成。
 *
 * <p>合法标识规则：长度 8~64，仅允许小写十六进制字符、数字、小写字母及 '-'、'_'，
 * 即 {@code ^[a-z0-9_-]{8,64}$}。满足规则的入站标识原样沿用；
 * 未携带或不合法时生成形如 {@code trace-<16 字节十六进制>} 的新标识。
 */
public final class TraceIdGenerator {

    private static final java.util.regex.Pattern VALID =
            java.util.regex.Pattern.compile("^[a-z0-9_-]{8,64}$");

    private static final SecureRandom RANDOM = new SecureRandom();

    private TraceIdGenerator() {
    }

    public static boolean isValid(String traceId) {
        return traceId != null && VALID.matcher(traceId).matches();
    }

    public static String generate() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return "trace-" + HexFormat.of().formatHex(bytes);
    }

    /**
     * 沿用合法的入站标识，否则生成新标识。
     *
     * @return 非空的可用标识
     */
    public static String resolve(String inbound) {
        return isValid(inbound) ? inbound : generate();
    }
}
