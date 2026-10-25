package yov.metrics;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

public final class YOVMetrics {

    private final long startedNanos = System.nanoTime();
    private final LongAdder fastReads = new LongAdder();
    private final LongAdder fastReadHits = new LongAdder();
    private final LongAdder syncStarted = new LongAdder();
    private final LongAdder syncSucceeded = new LongAdder();
    private final LongAdder syncFailed = new LongAdder();
    private final LongAdder mutationAccepted = new LongAdder();
    private final LongAdder mutationSucceeded = new LongAdder();
    private final LongAdder mutationFailed = new LongAdder();
    private final LongAdder mutationRejected = new LongAdder();
    private final LongAdder offlineReads = new LongAdder();
    private final LongAdder offlineReadFailed = new LongAdder();
    private final LongAdder remoteOnlineRejected = new LongAdder();
    private final LongAdder limitRejected = new LongAdder();
    private final LongAdder backpressureRejected = new LongAdder();
    private final LongAdder syncDurationNanos = new LongAdder();
    private final AtomicLong syncDurationMaxNanos = new AtomicLong();
    private final AtomicInteger pendingOnline = new AtomicInteger();
    private final AtomicInteger peakPendingOnline = new AtomicInteger();
    private final AtomicInteger pendingOffline = new AtomicInteger();
    private final AtomicInteger peakPendingOffline = new AtomicInteger();

    public void fastRead(boolean hit) {
        fastReads.increment();
        if (hit) fastReadHits.increment();
    }

    public void syncStarted() {
        syncStarted.increment();
    }

    public void syncSucceeded(long durationNanos) {
        syncSucceeded.increment();
        recordSyncDuration(durationNanos);
    }

    public void syncFailed(long durationNanos) {
        syncFailed.increment();
        recordSyncDuration(durationNanos);
    }

    public void mutationAccepted(boolean offline) {
        mutationAccepted.increment();
        if (offline) incrementPending(pendingOffline, peakPendingOffline);
        else incrementPending(pendingOnline, peakPendingOnline);
    }

    public void mutationCompleted(boolean offline, boolean success) {
        if (success) mutationSucceeded.increment();
        else mutationFailed.increment();
        AtomicInteger pending = offline ? pendingOffline : pendingOnline;
        pending.updateAndGet(value -> Math.max(0, value - 1));
    }

    public void mutationRejected(RejectReason reason) {
        mutationRejected.increment();
        switch (reason) {
            case LIMIT -> limitRejected.increment();
            case BACKPRESSURE -> backpressureRejected.increment();
            case REMOTE_ONLINE -> remoteOnlineRejected.increment();
            case OTHER -> {
            }
        }
    }

    public void apiBackpressureRejected() {
        backpressureRejected.increment();
    }

    public void offlineRead(boolean failed) {
        offlineReads.increment();
        if (failed) offlineReadFailed.increment();
    }

    public Snapshot snapshot(int sessions, int readySessions, int syncingSessions) {
        long succeeded = syncSucceeded.sum();
        long failed = syncFailed.sum();
        long completed = succeeded + failed;
        long totalSyncNanos = syncDurationNanos.sum();
        return new Snapshot(
                Duration.ofNanos(Math.max(0L, System.nanoTime() - startedNanos)),
                sessions,
                readySessions,
                syncingSessions,
                fastReads.sum(),
                fastReadHits.sum(),
                syncStarted.sum(),
                succeeded,
                failed,
                completed == 0L ? 0L : Duration.ofNanos(totalSyncNanos / completed).toMillis(),
                Duration.ofNanos(syncDurationMaxNanos.get()).toMillis(),
                mutationAccepted.sum(),
                mutationSucceeded.sum(),
                mutationFailed.sum(),
                mutationRejected.sum(),
                offlineReads.sum(),
                offlineReadFailed.sum(),
                remoteOnlineRejected.sum(),
                limitRejected.sum(),
                backpressureRejected.sum(),
                pendingOnline.get(),
                peakPendingOnline.get(),
                pendingOffline.get(),
                peakPendingOffline.get());
    }

    private void recordSyncDuration(long nanos) {
        if (nanos <= 0L) return;
        syncDurationNanos.add(nanos);
        syncDurationMaxNanos.accumulateAndGet(nanos, Math::max);
    }

    private static void incrementPending(AtomicInteger pending, AtomicInteger peak) {
        int value = pending.incrementAndGet();
        peak.accumulateAndGet(value, Math::max);
    }

    public enum RejectReason {
        LIMIT,
        BACKPRESSURE,
        REMOTE_ONLINE,
        OTHER
    }

    public record Snapshot(Duration uptime,
                           int sessions,
                           int readySessions,
                           int syncingSessions,
                           long fastReads,
                           long fastReadHits,
                           long syncStarted,
                           long syncSucceeded,
                           long syncFailed,
                           long averageSyncMillis,
                           long maxSyncMillis,
                           long mutationsAccepted,
                           long mutationsSucceeded,
                           long mutationsFailed,
                           long mutationsRejected,
                           long offlineReads,
                           long offlineReadFailed,
                           long remoteOnlineRejected,
                           long limitRejected,
                           long backpressureRejected,
                           int pendingOnline,
                           int peakPendingOnline,
                           int pendingOffline,
                           int peakPendingOffline) {
    }
}
