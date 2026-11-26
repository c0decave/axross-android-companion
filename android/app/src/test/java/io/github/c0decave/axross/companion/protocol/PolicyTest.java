package io.github.c0decave.axross.companion.protocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import org.junit.Test;

public class PolicyTest {
    private static PeerPolicy.Source uid(final int uid) {
        return new PeerPolicy.Source() {
            @Override
            public int peerUid() {
                return uid;
            }
        };
    }

    @Test
    public void onlyShellAndRoot() {
        assertTrue(PeerPolicy.allowed(2000));
        assertTrue(PeerPolicy.allowed(0));
        for (int uid : new int[] {1, 1000, 1001, 1999, 2001, 9999, 10000, 10123, 99999, 1010123, -1, Integer.MAX_VALUE}) {
            assertFalse("uid " + uid, PeerPolicy.allowed(uid));
        }
        assertEquals(2000, PeerPolicy.admit(uid(2000)));
        assertEquals(0, PeerPolicy.admit(uid(0)));
        assertEquals(-1, PeerPolicy.admit(uid(10123)));
    }

    @Test
    public void aFailingCheckRefuses() {
        assertEquals(-1, PeerPolicy.admit(new PeerPolicy.Source() {
            @Override
            public int peerUid() throws IOException {
                throw new IOException("no credentials");
            }
        }));
        assertEquals(-1, PeerPolicy.admit(new PeerPolicy.Source() {
            @Override
            public int peerUid() {
                throw new IllegalStateException("closed");
            }
        }));
    }

    @Test
    public void rateLimitPerMinuteAndHour() {
        final long[] now = {1_000_000};
        RateLimiter r = new RateLimiter(20, 100, new RateLimiter.Clock() {
            @Override
            public long nowMs() {
                return now[0];
            }
        });
        assertEquals(0, r.tryAcquire(10));
        now[0] += 10_000;
        assertEquals(0, r.tryAcquire(10));
        // (the minute is full: the first ten leave it 50 s from now)
        assertEquals(50_000, r.tryAcquire(1));
        now[0] += 50_000;
        assertEquals(0, r.tryAcquire(10));
        // a request is taken whole or not at all
        long wait = r.tryAcquire(11);
        assertTrue(wait > 0);
        // the hour: 100 at most
        for (int i = 0; i < 7; i++) {
            now[0] += 61_000;
            assertEquals(0, r.tryAcquire(10));
        }
        now[0] += 61_000;
        long hourWait = r.tryAcquire(10);
        assertTrue("hour window " + hourWait, hourWait > 60_000);
        now[0] += hourWait;
        assertEquals(0, r.tryAcquire(10));
    }

    @Test
    public void tooManyForAMinuteIsNeverTaken() {
        RateLimiter r = new RateLimiter(20, 100, new RateLimiter.Clock() {
            @Override
            public long nowMs() {
                return 5;
            }
        });
        assertTrue(r.tryAcquire(21) > 0);
        assertEquals(0, r.tryAcquire(20));
    }
}
