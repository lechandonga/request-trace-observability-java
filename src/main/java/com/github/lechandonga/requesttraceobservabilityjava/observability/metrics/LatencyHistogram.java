package com.github.lechandonga.requesttraceobservabilityjava.observability.metrics;

import java.util.Arrays;
import java.util.concurrent.atomic.LongAccumulator;
import java.util.concurrent.atomic.LongAdder;

/**
 * 固定桶边界的延迟直方图（毫秒），所有更新都是无锁原子操作，并发下不丢数。
 *
 * <p>分位数由桶计数累计插值得到（近似值，口径见 OBSERVABILITY.md）：
 * 采用“落入桶的样本按桶上界取值”的方式，p50/p95/p99 均为保守上界近似。
 */
public class LatencyHistogram {

    static final long[] BUCKET_BOUNDS_MS = {
            1, 2, 5, 10, 25, 50, 100, 250, 500, 1000, 2500, 5000, 10000
    };

    private final LongAdder[] bucketCounts;
    private final LongAdder totalSumMs = new LongAdder();
    private final LongAdder count = new LongAdder();
    private final LongAccumulator minMs = new LongAccumulator(Math::min, Long.MAX_VALUE);
    private final LongAccumulator maxMs = new LongAccumulator(Math::max, 0L);

    public LatencyHistogram() {
        this.bucketCounts = new LongAdder[BUCKET_BOUNDS_MS.length + 1];
        for (int i = 0; i < bucketCounts.length; i++) {
            bucketCounts[i] = new LongAdder();
        }
    }

    public void record(long latencyMs) {
        long safeLatency = Math.max(0L, latencyMs);
        bucketCounts[bucketIndexOf(safeLatency)].increment();
        totalSumMs.add(safeLatency);
        count.increment();
        minMs.accumulate(safeLatency);
        maxMs.accumulate(safeLatency);
    }

    static int bucketIndexOf(long latencyMs) {
        int idx = Arrays.binarySearch(BUCKET_BOUNDS_MS, latencyMs);
        if (idx < 0) {
            idx = -idx - 1;
        }
        return idx;
    }

    public Snapshot snapshot() {
        long total = count.sum();
        long sum = totalSumMs.sum();
        long[] counts = new long[bucketCounts.length];
        for (int i = 0; i < counts.length; i++) {
            counts[i] = bucketCounts[i].sum();
        }
        long min = total == 0 ? -1L : minMs.get();
        long max = total == 0 ? -1L : maxMs.get();
        double avg = total == 0 ? 0d : (double) sum / total;
        return new Snapshot(total, min, max, Math.round(avg * 100d) / 100d,
                quantile(counts, total, 0.50),
                quantile(counts, total, 0.95),
                quantile(counts, total, 0.99),
                counts);
    }

    private static long quantile(long[] counts, long total, double q) {
        if (total == 0) {
            return -1L;
        }
        long target = Math.max(1L, (long) Math.ceil(q * total));
        long cumulative = 0L;
        for (int i = 0; i < counts.length; i++) {
            cumulative += counts[i];
            if (cumulative >= target) {
                return i < BUCKET_BOUNDS_MS.length ? BUCKET_BOUNDS_MS[i] : -1L;
            }
        }
        return -1L;
    }

    /**
     * @param sampleCount 样本数
     * @param minMs       最小延迟，无样本时为 -1
     * @param maxMs       最大延迟，无样本时为 -1
     * @param avgMs       平均延迟
     * @param p50Ms       p50 近似值
 * @param p95Ms       p95 近似值
 * @param p99Ms       p99 近似值
     * @param bucketCounts 各桶样本数，最后一个为 +Inf 桶（&gt;10000ms）
     */
    public record Snapshot(
            long sampleCount,
            long minMs,
            long maxMs,
            double avgMs,
            long p50Ms,
            long p95Ms,
            long p99Ms,
            long[] bucketCounts
    ) {
    }
}
