package io.github.c0decave.axross.companion.protocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

/** The send store on disk: what survives a restart of the service (a new store over the same files). */
public class SendStoreTest {
    static final class Clock implements RateLimiter.Clock {
        long now = 1_764_150_000_000L;

        @Override
        public long nowMs() {
            return now;
        }
    }

    static File dir() throws IOException {
        return Files.createTempDirectory("sends").toFile();
    }

    static SendStore open(File dir, Clock clock) {
        return SendStore.open(dir, clock, new SecureRandom());
    }

    private static final List<String> ONE = Arrays.asList("+15555215554");
    private static final List<String> TWO = Arrays.asList("+15555215554", "+15555215555");

    @Test
    public void aNewStoreHasAnIdAndARandomFirstSendId() throws Exception {
        Clock clock = new Clock();
        File dir = dir();
        SendStore s = open(dir, clock);
        assertEquals(SendStore.Origin.NEW, s.origin());
        assertTrue(Tokens.isHex(s.id(), 32));
        assertTrue(new File(dir, SendStore.FILE).isFile());
        long first = s.add("r1", ONE, Arrays.asList(1)).id;
        assertTrue(first >= 1 && first <= (1L << 31));
        // (another new store: another id, another start)
        SendStore other = open(dir(), clock);
        assertNotEquals(s.id(), other.id());
        // the same files: the same store, the counter goes on
        SendStore again = open(dir, clock);
        assertEquals(SendStore.Origin.LOADED, again.origin());
        assertEquals(s.id(), again.id());
        assertEquals(first + 1, again.add("r2", ONE, Arrays.asList(1)).id);
        assertEquals(first + 2, open(dir, clock).add(null, ONE, Arrays.asList(1)).id);
    }

    @Test
    public void sendsAndTheirStatesSurviveARestart() throws Exception {
        Clock clock = new Clock();
        File dir = dir();
        SendStore s = open(dir, clock);
        SendStore.Send send = s.add("ref-1", TWO, Arrays.asList(2, 1));
        // first recipient: one part of two sent; second: failed
        assertNull(s.sent(send.id, 0, 0, true, -1));
        SendStore.Event failed = s.sent(send.id, 1, 0, false, 2);
        assertEquals("sms.failed", failed.name);
        assertEquals(Json.obj("send_id", send.id, "index", 1L, "address", "+15555215555", "reason", "radio_off",
                "result_code", 2L, "client_ref", "ref-1"), failed.data);

        // the service stops; a new run reads the files
        SendStore t = open(dir, clock);
        SendStore.Send back = t.byRef("ref-1");
        assertNotNull(back);
        assertEquals(send.id, back.id);
        assertEquals(send.accepted, back.accepted);
        assertEquals("+15555215554", back.recipient(0).address);
        assertEquals(2, back.recipient(0).parts);
        assertEquals("sending", t.state(back, 0));
        assertEquals("failed", t.state(back, 1));
        assertEquals("radio_off", t.reason(back, 1));
        // the part already reported counts: the second completes the recipient
        assertNull(t.sent(send.id, 0, 0, true, -1));
        SendStore.Event sent = t.sent(send.id, 0, 1, true, -1);
        assertEquals("sms.sent", sent.name);
        assertEquals(Json.obj("send_id", send.id, "index", 0L, "address", "+15555215554", "client_ref", "ref-1"), sent.data);
        // and its delivery, in a third run
        SendStore u = open(dir, clock);
        assertEquals("sent", u.state(u.byId(send.id), 0));
        assertNull(u.delivered(send.id, 0, 0, "complete"));
        assertEquals("complete", u.delivered(send.id, 0, 1, "complete").data.get("status"));
        assertEquals("delivered", open(dir, clock).state(open(dir, clock).byId(send.id), 0));
    }

    @Test
    public void theFileHoldsNoText() throws Exception {
        File dir = dir();
        SendStore s = open(dir, new Clock());
        // (the store is never given the text: only addresses and part counts)
        s.add("ref-1", ONE, Arrays.asList(3));
        String file = new String(Files.readAllBytes(new File(dir, SendStore.FILE).toPath()), StandardCharsets.UTF_8);
        Json.parseObject(file);
        assertTrue(file.contains("+15555215554"));
        assertFalse(file.contains("body"));
        assertFalse(file.contains("text"));
    }

    @Test
    public void aJournalReplayedTwiceChangesNothing() throws Exception {
        Clock clock = new Clock();
        File dir = dir();
        SendStore s = open(dir, clock);
        SendStore.Send a = s.add("a", ONE, Arrays.asList(3));
        s.sent(a.id, 0, 0, true, -1);
        s.sent(a.id, 0, 1, true, -1);
        byte[] journal = Files.readAllBytes(new File(dir, SendStore.JOURNAL).toPath());
        // (a send accepted: sends.json holds the journal's lines now, the journal is emptied ...)
        s.add("b", ONE, Arrays.asList(1));
        assertFalse(new File(dir, SendStore.JOURNAL).exists());
        // (... but a crash left the old journal there)
        Files.write(new File(dir, SendStore.JOURNAL).toPath(), journal);
        SendStore t = open(dir, clock);
        assertEquals("sending", t.state(t.byRef("a"), 0));
        assertEquals("sms.sent", t.sent(a.id, 0, 2, true, -1).name);
        // a report given twice (or replayed) does not count twice
        assertNull(t.sent(a.id, 0, 2, true, -1));
    }

    @Test
    public void aTornJournalLineIsIgnored() throws Exception {
        Clock clock = new Clock();
        File dir = dir();
        SendStore s = open(dir, clock);
        SendStore.Send a = s.add("a", ONE, Arrays.asList(2));
        s.sent(a.id, 0, 0, true, -1);
        try (FileOutputStream out = new FileOutputStream(new File(dir, SendStore.JOURNAL), true)) {
            out.write("{\"q\": 99, \"t\": 1, \"s\": ".getBytes(StandardCharsets.UTF_8));
        }
        SendStore t = open(dir, clock);
        assertEquals(SendStore.Origin.LOADED, t.origin());
        assertEquals(s.id(), t.id());
        assertEquals("sms.sent", t.sent(a.id, 0, 1, true, -1).name);
    }

    @Test
    public void aCorruptStoreIsMadeAnewWithANewId() throws Exception {
        Clock clock = new Clock();
        File dir = dir();
        SendStore s = open(dir, clock);
        s.add("a", ONE, Arrays.asList(1));
        for (String bad : new String[] {"", "{", "[]", "{\"format\": \"axross-companion-sends\", \"version\": 2}",
                "{\"format\": \"axross-companion-sends\", \"version\": 1, \"store\": \"x\", \"next_send_id\": 1, \"seq\": 0, \"sends\": []}"}) {
            Files.write(new File(dir, SendStore.FILE).toPath(), bad.getBytes(StandardCharsets.UTF_8));
            SendStore t = open(dir, clock);
            assertEquals(bad, SendStore.Origin.UNREADABLE, t.origin());
            assertNotEquals(bad, s.id(), t.id());
            assertNull(bad, t.byRef("a"));
            assertEquals(0, t.size());
            // (and that new store is what the next run reads)
            assertEquals(t.id(), open(dir, clock).id());
        }
        // gone (app data cleared): a new store too
        new File(dir, SendStore.FILE).delete();
        SendStore n = open(dir, clock);
        assertEquals(SendStore.Origin.NEW, n.origin());
    }

    @Test
    public void keptForKeepMsThenDropped() throws Exception {
        Clock clock = new Clock();
        File dir = dir();
        SendStore s = open(dir, clock);
        SendStore.Send a = s.add("a", ONE, Arrays.asList(1));
        clock.now += SendStore.KEEP_MS;
        assertNotNull(open(dir, clock).byRef("a"));
        assertNotNull(s.byId(a.id));
        // (a little longer: a phone clock set forward does not drop it early)
        clock.now += SendStore.GRACE_MS - 1;
        assertNotNull(s.byRef("a"));
        clock.now += 2;
        assertNull(s.byRef("a"));
        assertNull(s.byId(a.id));
        assertNull(open(dir, clock).byRef("a"));
        // and the ref may be used again
        assertNotEquals(a.id, s.add("a", ONE, Arrays.asList(1)).id);
    }

    @Test
    public void boundedTheOldestDroppedFirst() throws Exception {
        Clock clock = new Clock();
        File dir = dir();
        SendStore s = open(dir, clock);
        s.maxSends = 3;
        for (int i = 0; i < 5; i++) {
            s.add("r" + i, ONE, Arrays.asList(1));
            clock.now += 1000;
        }
        assertEquals(3, s.size());
        assertNull(s.byRef("r0"));
        assertNull(s.byRef("r1"));
        assertNotNull(s.byRef("r2"));
        assertNotNull(s.byRef("r4"));
        assertEquals(3, open(dir, clock).size());
        assertEquals(5000, SendStore.MAX_SENDS);
    }

    @Test
    public void aStoreThatCannotBeWrittenRecordsNothing() throws Exception {
        File notADir = File.createTempFile("sends", ".file");
        SendStore s = open(notADir, new Clock());
        assertEquals(1, s.writeFailures());
        try {
            s.add("a", ONE, Arrays.asList(1));
            fail("written");
        } catch (IOException expected) {
            // (nothing recorded)
        }
        assertNull(s.byRef("a"));
        assertEquals(0, s.size());
    }

    @Test
    public void stillSendingAnHourAfterAcceptanceIsUnknown() throws Exception {
        Clock clock = new Clock();
        File dir = dir();
        SendStore s = open(dir, clock);
        SendStore.Send a = s.add("a", TWO, Arrays.asList(1, 1));
        s.sent(a.id, 1, 0, true, -1);
        clock.now += SendStore.UNKNOWN_AFTER_MS - 1;
        assertEquals("sending", s.state(a, 0));
        clock.now += 1;
        assertEquals("unknown", s.state(a, 0));
        assertEquals("sent", s.state(a, 1));
        // a report that comes now is ignored: it stays unknown, also after a restart
        assertNull(s.sent(a.id, 0, 0, true, -1));
        assertNull(s.delivered(a.id, 0, 0, "complete"));
        assertEquals("unknown", s.state(a, 0));
        SendStore t = open(dir, clock);
        assertEquals("unknown", t.state(t.byRef("a"), 0));
        // (a sent one still gets its delivery report)
        assertEquals("sms.delivered", t.delivered(a.id, 1, 0, "complete").name);
    }

    @Test
    public void reportsForOtherSendsAreIgnored() throws Exception {
        SendStore s = open(dir(), new Clock());
        SendStore.Send a = s.add(null, ONE, Arrays.asList(2));
        assertNull(s.sent(a.id + 1, 0, 0, true, -1));
        assertNull(s.sent(a.id, 1, 0, true, -1));
        assertNull(s.sent(a.id, 0, 2, true, -1));
        assertNull(s.sent(a.id, 0, -1, true, -1));
        assertNull(s.delivered(a.id, 0, 5, "complete"));
        // a delivery report "pending" changes no state
        assertNull(s.delivered(a.id, 0, 0, "pending"));
        assertEquals("sending", s.state(a, 0));
        // without a client_ref: the events have none
        s.sent(a.id, 0, 0, true, -1);
        assertFalse(s.sent(a.id, 0, 1, true, -1).data.containsKey("client_ref"));
    }

    @Test
    public void sendIdsDoNotRepeatAcrossRuns() throws Exception {
        Clock clock = new Clock();
        File dir = dir();
        Set<Long> ids = new HashSet<>();
        for (int run = 0; run < 20; run++) {
            SendStore s = open(dir, clock);
            for (int i = 0; i < 5; i++) {
                assertTrue(ids.add(s.add(null, ONE, Arrays.asList(1)).id));
            }
        }
        for (long id : ids) {
            assertTrue(id >= 1 && id <= SendStore.MAX_SEND_ID);
        }
    }

    @Test
    public void theJournalIsCompacted() throws Exception {
        Clock clock = new Clock();
        File dir = dir();
        SendStore s = open(dir, clock);
        List<String> ten = new java.util.ArrayList<>();
        List<Integer> parts = new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++) {
            ten.add("+1555521000" + i);
            parts.add(30);
        }
        SendStore.Send a = s.add("a", ten, parts);
        SendStore.Send b = s.add("b", ten, parts);
        // 2 sends x 10 recipients x 30 parts x (sent, delivered): 1200 journal lines
        int events = 0;
        for (SendStore.Send x : new SendStore.Send[] {a, b}) {
            for (int i = 0; i < 10; i++) {
                for (int p = 0; p < 30; p++) {
                    events += s.sent(x.id, i, p, true, -1) == null ? 0 : 1;
                    events += s.delivered(x.id, i, p, "complete") == null ? 0 : 1;
                }
            }
        }
        assertEquals(40, events);
        String journal = new String(Files.readAllBytes(new File(dir, SendStore.JOURNAL).toPath()), StandardCharsets.UTF_8);
        assertEquals(1200 - SendStore.COMPACT_AFTER, journal.split("\n").length);
        SendStore t = open(dir, clock);
        for (SendStore.Send x : new SendStore.Send[] {a, b}) {
            for (int i = 0; i < 10; i++) {
                assertEquals("delivered", t.state(t.byId(x.id), i));
            }
        }
    }

    // -- revision 2: next_id and dropped_through --------------------------------------------

    private static long nextId(SendStore s) {
        return (Long) s.describe().get("next_id");
    }

    private static long droppedThrough(SendStore s) {
        return (Long) s.describe().get("dropped_through");
    }

    private static Map<String, Object> file(File dir) throws Exception {
        return Json.parseObject(new String(Files.readAllBytes(new File(dir, SendStore.FILE).toPath()), StandardCharsets.UTF_8));
    }

    @Test
    public void describeIsHellosSends() throws Exception {
        Clock clock = new Clock();
        SendStore s = open(dir(), clock);
        long first = nextId(s);
        assertEquals(Json.obj("store", s.id(), "keep_ms", SendStore.KEEP_MS, "next_id", first, "dropped_through", 0L),
                s.describe());
        assertEquals(s.describe(), Protocol.sends(s));
        assertEquals(first, s.add("a", ONE, Arrays.asList(1)).id);
        assertEquals(first + 1, nextId(s));
        assertEquals(Json.obj("store", s.id(), "dropped_through", 0L), s.notFound());
    }

    @Test
    public void droppedThroughSurvivesARestart() throws Exception {
        Clock clock = new Clock();
        File dir = dir();
        SendStore s = open(dir, clock);
        SendStore.Send a = s.add("a", ONE, Arrays.asList(1));
        clock.now += 1000;
        SendStore.Send b = s.add("b", ONE, Arrays.asList(1));
        clock.now += SendStore.KEEP_MS + SendStore.GRACE_MS - 500;
        // (a is past keep_ms and the grace, b not yet)
        assertNull(s.byRef("a"));
        assertNotNull(s.byRef("b"));
        assertEquals(a.id, droppedThrough(s));
        // on disk with the removal: the file has no a, and says so
        Map<String, Object> f = file(dir);
        assertEquals(a.id, f.get("dropped_through"));
        assertEquals(1, ((List<?>) f.get("sends")).size());
        // the next run reads it - also with the phone's clock set back, a stays dropped
        clock.now -= 7 * SendStore.KEEP_MS;
        SendStore t = open(dir, clock);
        assertEquals(SendStore.Origin.LOADED, t.origin());
        assertEquals(a.id, droppedThrough(t));
        assertNull(t.byRef("a"));
        assertNotNull(t.byRef("b"));
        assertEquals(b.id + 1, nextId(t));
        // a new store (data cleared) starts at 0 again, with a new id
        new File(dir, SendStore.FILE).delete();
        SendStore n = open(dir, clock);
        assertNotEquals(s.id(), n.id());
        assertEquals(0, droppedThrough(n));
    }

    @Test
    public void droppedThroughNeverDecreases() throws Exception {
        Clock clock = new Clock();
        File dir = dir();
        SendStore s = open(dir, clock);
        long start = clock.now;
        SendStore.Send a = s.add("a", ONE, Arrays.asList(1));
        // the phone's clock goes back two days: b, a higher id, is "older" than a
        clock.now = start - 2 * SendStore.KEEP_MS;
        SendStore.Send b = s.add("b", ONE, Arrays.asList(1));
        assertTrue(b.id > a.id);
        // b expires first (ids need not be dropped in order) ...
        clock.now = b.accepted + SendStore.KEEP_MS + SendStore.GRACE_MS + 1;
        assertNull(s.byRef("b"));
        assertNotNull(s.byRef("a"));
        assertEquals(b.id, droppedThrough(s));
        // ... then a, a lower id: dropped_through stays b's
        clock.now = a.accepted + SendStore.KEEP_MS + SendStore.GRACE_MS + 1;
        assertNull(s.byRef("a"));
        assertEquals(b.id, droppedThrough(s));
        assertEquals(b.id, droppedThrough(open(dir, clock)));
        // and the cap dropping a lower id later does not lower it either
        SendStore t = open(dir, clock);
        t.maxSends = 1;
        long c = t.add("c", ONE, Arrays.asList(1)).id;
        t.add("d", ONE, Arrays.asList(1));
        assertEquals(c, droppedThrough(t));
        assertTrue(c > b.id);
        long[] seen = {0};
        for (int i = 0; i < 20; i++) {
            clock.now += SendStore.KEEP_MS / 4;
            t.add("e" + i, ONE, Arrays.asList(1));
            long now = droppedThrough(t);
            assertTrue(now >= seen[0]);
            seen[0] = now;
            assertEquals(now, droppedThrough(open(dir, clock)));
        }
    }

    @Test
    public void theCapCountsWhatItDrops() throws Exception {
        Clock clock = new Clock();
        File dir = dir();
        SendStore s = open(dir, clock);
        s.maxSends = 3;
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            ids.add(s.add("r" + i, ONE, Arrays.asList(1)).id);
        }
        assertEquals(0, droppedThrough(s));
        ids.add(s.add("r3", ONE, Arrays.asList(1)).id);
        // r0 went in the same write that added r3
        assertNull(s.byRef("r0"));
        assertEquals((long) ids.get(0), droppedThrough(s));
        Map<String, Object> f = file(dir);
        assertEquals(ids.get(0), f.get("dropped_through"));
        assertEquals(ids.get(3) + 1, (long) (Long) f.get("next_send_id"));
        assertEquals(3, ((List<?>) f.get("sends")).size());
        ids.add(s.add("r4", ONE, Arrays.asList(1)).id);
        assertEquals((long) ids.get(1), droppedThrough(s));
        assertEquals((long) ids.get(1), droppedThrough(open(dir, clock)));
        // every id below next_id is kept or counted: the client's rule
        long next = nextId(s);
        for (long id = ids.get(0); id < next; id++) {
            assertTrue(s.byId(id) != null || id <= droppedThrough(s));
        }
    }

    @Test
    public void aForwardClockJumpShowsInDroppedThrough() throws Exception {
        Clock clock = new Clock();
        File dir = dir();
        SendStore s = open(dir, clock);
        s.add("before", ONE, Arrays.asList(1));
        // hello: F; the session's send; the answer is lost
        long f = nextId(s);
        SendStore.Send mine = s.add("mine", ONE, Arrays.asList(1));
        assertTrue(mine.id >= f);
        // the phone's clock jumps 30 days ahead (set by hand); the client asks minutes later
        clock.now += 30 * SendStore.KEEP_MS;
        assertNull(s.byRef("mine"));
        Map<String, Object> nf = s.notFound();
        assertEquals(s.id(), nf.get("store"));
        assertTrue((Long) nf.get("dropped_through") >= f);
        assertEquals(mine.id, nf.get("dropped_through"));
        // the clock is put right and the service restarts: still counted, still gone
        clock.now -= 30 * SendStore.KEEP_MS;
        SendStore t = open(dir, clock);
        assertNull(t.byRef("mine"));
        assertEquals(mine.id, droppedThrough(t));
        // a ref of a session after it (new F) that never came: dropped_through < F
        long g = nextId(t);
        assertTrue(droppedThrough(t) < g);
    }

    @Test
    public void aDropIsOnDiskBeforeTheSendIsGone() throws Exception {
        Clock clock = new Clock();
        File dir = dir();
        SendStore s = open(dir, clock);
        SendStore.Send a = s.add("a", ONE, Arrays.asList(1));
        clock.now += SendStore.KEEP_MS + SendStore.GRACE_MS + 1;
        // sends.json cannot be written (its temporary file's name is taken by a directory)
        File block = new File(dir, SendStore.TEMP);
        assertTrue(block.mkdir());
        int failures = s.writeFailures();
        // the send stays (found, which is true), dropped_through is not raised
        assertNotNull(s.byRef("a"));
        assertEquals(0, droppedThrough(s));
        assertEquals(Json.obj("store", s.id(), "dropped_through", 0L), s.notFound());
        assertTrue(s.writeFailures() > failures);
        // a "crash": the next run reads what is on disk - the send, not dropped
        SendStore crashed = open(dir, new Clock());
        assertNotNull(crashed.byRef("a"));
        assertEquals(0, droppedThrough(crashed));
        // writable again: after the next write that works (an accepted send), it is dropped and counted
        assertTrue(block.delete());
        SendStore.Send b = s.add("b", ONE, Arrays.asList(1));
        assertNull(s.byRef("a"));
        assertNotNull(s.byRef("b"));
        assertEquals(a.id, droppedThrough(s));
        assertEquals(a.id, droppedThrough(open(dir, clock)));
        assertTrue(b.id > a.id);
    }

    @Test
    public void aStoreOf012CountsWhatItMayHaveDropped() throws Exception {
        Clock clock = new Clock();
        String sends = "[" + send012(1000) + "," + send012(1002) + "," + send012(1003) + "]";
        // (no dropped_through: 1001 is missing, and 1000 might not be the first)
        File dir = dir();
        write012(dir, 1004, sends);
        SendStore s = open(dir, clock);
        assertEquals(SendStore.Origin.LOADED, s.origin());
        assertEquals(1001, droppedThrough(s));
        assertEquals(1004, nextId(s));
        // written back with the field
        s.add("x", ONE, Arrays.asList(1));
        assertEquals(1001L, file(dir).get("dropped_through"));
        // all ids up to next kept: the one below the lowest counts; none kept: next - 1
        File d2 = dir();
        write012(d2, 1004, "[" + send012(1002) + "," + send012(1003) + "]");
        assertEquals(1001, droppedThrough(open(d2, clock)));
        File d3 = dir();
        write012(d3, 1004, "[]");
        assertEquals(1003, droppedThrough(open(d3, clock)));
        File d4 = dir();
        write012(d4, 1, "[]");
        assertEquals(0, droppedThrough(open(d4, clock)));
    }

    private static String send012(long id) {
        return "{\"send_id\": " + id + ", \"client_ref\": \"r" + id + "\", \"accepted\": 1764150000000, \"recipients\": "
                + "[{\"address\": \"+15555215554\", \"parts\": 1, \"sent_parts\": 1, \"delivered_parts\": 0, "
                + "\"delivery_pending\": false, \"state\": \"sent\", \"reason\": null}]}";
    }

    private static void write012(File dir, long next, String sends) throws IOException {
        String doc = "{\"format\": \"axross-companion-sends\", \"version\": 1, \"store\": \"6feebacd6df89e21d7171200838883d5\", "
                + "\"next_send_id\": " + next + ", \"seq\": 0, \"sends\": " + sends + "}";
        Files.write(new File(dir, SendStore.FILE).toPath(), doc.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void badCountersMakeTheStoreAnew() throws Exception {
        Clock clock = new Clock();
        String base = "{\"format\": \"axross-companion-sends\", \"version\": 1, \"store\": \"6feebacd6df89e21d7171200838883d5\", "
                + "\"seq\": 0, ";
        for (String bad : new String[] {
                // dropped_through at or above next_send_id, or negative
                "\"next_send_id\": 5, \"dropped_through\": 5, \"sends\": []}",
                "\"next_send_id\": 5, \"dropped_through\": -1, \"sends\": []}",
                // a send at or above next_send_id (ids would repeat)
                "\"next_send_id\": 5, \"dropped_through\": 0, \"sends\": [" + send012(5) + "]}"}) {
            File dir = dir();
            Files.write(new File(dir, SendStore.FILE).toPath(), (base + bad).getBytes(StandardCharsets.UTF_8));
            SendStore s = open(dir, clock);
            assertEquals(bad, SendStore.Origin.UNREADABLE, s.origin());
            assertNotEquals(bad, "6feebacd6df89e21d7171200838883d5", s.id());
        }
    }

    @Test
    public void sendIdsNeverWrap() throws Exception {
        Clock clock = new Clock();
        File dir = dir();
        SendStore s = SendStore.create(dir, "6feebacd6df89e21d7171200838883d5", SendStore.MAX_SEND_ID, clock);
        assertEquals(SendStore.MAX_SEND_ID, s.add("last", ONE, Arrays.asList(1)).id);
        try {
            s.add("after", ONE, Arrays.asList(1));
            fail("an id past the last");
        } catch (IOException expected) {
            // (internal: not sent)
        }
        SendStore t = open(dir, clock);
        assertEquals(SendStore.Origin.LOADED, t.origin());
        assertEquals(SendStore.MAX_SEND_ID + 1, nextId(t));
        assertNotNull(t.byRef("last"));
        assertNull(t.byRef("after"));
    }

    /**
     * hello's next_id is read under the lock ids are given out under: a
     * send accepted after it was read never has a lower id, and no id
     * given out before it is at or above it.
     */
    @Test
    public void nextIdIsReadUnderTheAllocationLock() throws Exception {
        final Clock clock = new Clock();
        final SendStore s = open(dir(), clock);
        final List<Long> all = Collections.synchronizedList(new ArrayList<Long>());
        ExecutorService pool = Executors.newFixedThreadPool(6);
        List<Future<?>> jobs = new ArrayList<>();
        for (int w = 0; w < 6; w++) {
            final int worker = w;
            jobs.add(pool.submit(() -> {
                long last = 0;
                for (int i = 0; i < 150; i++) {
                    // the highest id this thread knows was given out before the "hello"
                    long known = last;
                    long f = (Long) s.describe().get("next_id");
                    assertTrue("an id given out before is below next_id", known < f);
                    long id;
                    try {
                        id = s.add(worker + "-" + i, ONE, Arrays.asList(1)).id;
                    } catch (IOException e) {
                        throw new AssertionError(e);
                    }
                    assertTrue("a send after hello has an id >= next_id", id >= f);
                    assertTrue("ids grow", id > last);
                    last = id;
                    all.add(id);
                }
                return null;
            }));
        }
        for (Future<?> j : jobs) {
            j.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertEquals(900, new HashSet<>(all).size());
        long min = Collections.min(all);
        long max = Collections.max(all);
        // consecutive: one counter, no gaps, no repeats
        assertEquals(899, max - min);
        assertEquals(max + 1, nextId(s));
    }
}
