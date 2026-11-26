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
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
}
