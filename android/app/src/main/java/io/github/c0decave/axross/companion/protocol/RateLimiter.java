package io.github.c0decave.axross.companion.protocol;

import java.util.ArrayDeque;

/**
 * Sliding windows over the messages sent (one a recipient): at most
 * {@code perMinute} in any 60 s and {@code perHour} in any hour. A request
 * is taken whole or not at all.
 */
public final class RateLimiter {
    /** A clock in milliseconds (injectable for the tests). */
    public interface Clock {
        long nowMs();
    }

    private static final long MINUTE = 60_000L;
    private static final long HOUR = 3_600_000L;

    private final int perMinute;
    private final int perHour;
    private final Clock clock;
    private final ArrayDeque<Long> sent = new ArrayDeque<>();

    public RateLimiter(int perMinute, int perHour, Clock clock) {
        if (perMinute < 1 || perHour < perMinute) {
            throw new IllegalArgumentException("limits");
        }
        this.perMinute = perMinute;
        this.perHour = perHour;
        this.clock = clock;
    }

    /**
     * Takes {@code count} messages if both windows allow it.
     *
     * @return 0 when taken, else the milliseconds after which they would be
     */
    public synchronized long tryAcquire(int count) {
        if (count < 1) {
            throw new IllegalArgumentException("count");
        }
        if (count > perMinute) {
            // (never possible: tell when the minute's window is empty)
            return MINUTE;
        }
        long now = clock.nowMs();
        while (!sent.isEmpty() && sent.peekFirst() <= now - HOUR) {
            sent.pollFirst();
        }
        long retry = 0;
        int inHour = sent.size();
        if (inHour + count > perHour) {
            retry = Math.max(retry, nth(inHour + count - perHour - 1) + HOUR - now);
        }
        int inMinute = 0;
        for (Long t : sent) {
            if (t > now - MINUTE) {
                inMinute++;
            }
        }
        if (inMinute + count > perMinute) {
            int firstInMinute = inHour - inMinute;
            retry = Math.max(retry, nth(firstInMinute + inMinute + count - perMinute - 1) + MINUTE - now);
        }
        if (retry > 0) {
            return retry;
        }
        for (int i = 0; i < count; i++) {
            sent.addLast(now);
        }
        return 0;
    }

    /**
     * Counts {@code count} messages sent at {@code timeMs} (earlier sends:
     * those of the send store, when the service starts again), in order.
     */
    public synchronized void record(long timeMs, int count) {
        java.util.ArrayList<Long> all = new java.util.ArrayList<>(sent);
        for (int i = 0; i < count; i++) {
            all.add(timeMs);
        }
        java.util.Collections.sort(all);
        sent.clear();
        sent.addAll(all);
    }

    private long nth(int index) {
        int i = 0;
        for (Long t : sent) {
            if (i++ == index) {
                return t;
            }
        }
        throw new IllegalStateException("index");
    }
}
