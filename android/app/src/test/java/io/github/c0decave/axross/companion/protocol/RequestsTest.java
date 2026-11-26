package io.github.c0decave.axross.companion.protocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class RequestsTest {
    /** protocol/fixtures/requests.json: every case as the shared definition says. */
    @Test
    @SuppressWarnings("unchecked")
    public void sharedFixtures() throws Exception {
        Map<String, Object> doc = Wire.fixture("fixtures/requests.json");
        List<Object> cases = (List<Object>) doc.get("cases");
        assertTrue(cases.size() > 50);
        for (Object o : cases) {
            Map<String, Object> c = (Map<String, Object>) o;
            String name = (String) c.get("name");
            String expect = (String) c.get("expect");
            try {
                Object value = Requests.parse((String) c.get("method"), new Params((Map<String, Object>) c.get("params")));
                assertEquals(name + ": passed", expect, "ok");
                assertTrue(name, value != null);
            } catch (RequestException e) {
                assertEquals(name, expect, e.code);
                if (c.containsKey("field")) {
                    assertEquals(name + ": field", c.get("field"), e.data == null ? null : e.data.get("field"));
                }
                // (no number or text in a message meant for people)
                assertFalse(name, e.getMessage().contains("555"));
            }
        }
    }

    @Test
    public void smsSendIsNormalized() throws Exception {
        Requests.SmsSend s = (Requests.SmsSend) Requests.parse("sms.send", new Params(Json.obj(
                "recipients", Arrays.asList("+1 (555) 521-5554", "0170 123.456/7"), "text", "Hi")));
        assertEquals(Arrays.asList("+15555215554", "01701234567"), s.recipients);
        assertEquals(-1, s.subId);
        assertTrue(s.deliveryReport);
    }

    @Test
    public void recipientRules() {
        assertEquals("+4917012345678", Recipients.normalize("+49 170 12345678"));
        assertEquals("112", Recipients.normalize("112"));
        assertNull(Recipients.normalize("++49"));
        assertNull(Recipients.normalize("+"));
        assertNull(Recipients.normalize("49 170 ext 5"));
        assertNull(Recipients.normalize("١٢٣"));
        assertNull(Recipients.normalize(""));
        assertNull(Recipients.normalize(null));
    }

    @Test
    public void helloChoosesTheHighestCommonVersion() throws Exception {
        Requests.Hello h = (Requests.Hello) Requests.parse("hello", new Params(Json.obj("protocols", Arrays.asList(5L, 1L, 2L),
                "client_nonce", Wire.CLIENT_NONCE, "token_id", Tokens.id(Wire.TOKEN))));
        assertEquals(2, h.choose());
        assertEquals(Wire.CLIENT_NONCE, h.clientNonce);
        assertEquals(Tokens.id(Wire.TOKEN), h.tokenId);
        // (a version-1 client hears of the version before anything else)
        try {
            Requests.parse("hello", new Params(Json.obj("protocols", Arrays.asList(1L))));
            fail("version 1");
        } catch (RequestException e) {
            assertEquals(ErrorCode.UNSUPPORTED_PROTOCOL, e.code);
            assertEquals(Arrays.asList(2L), e.data.get("supported"));
        }
    }

    @Test
    public void clientRefAndStatus() throws Exception {
        Requests.SmsSend s = (Requests.SmsSend) Requests.parse("sms.send", new Params(Json.obj(
                "recipients", Arrays.asList("+15555215554"), "text", "Hi", "client_ref", "0b6f-4C1d_x")));
        assertEquals("0b6f-4C1d_x", s.clientRef);
        s = (Requests.SmsSend) Requests.parse("sms.send", new Params(Json.obj(
                "recipients", Arrays.asList("+15555215554"), "text", "Hi")));
        assertNull(s.clientRef);
        Requests.SendQuery q = (Requests.SendQuery) Requests.parse("sms.status", new Params(Json.obj("client_ref", "r1")));
        assertEquals("r1", q.clientRef);
        q = (Requests.SendQuery) Requests.parse("sms.status", new Params(Json.obj("send_id", 3L)));
        assertNull(q.clientRef);
        assertEquals(3L, q.sendId);
        Requests.Auth a = (Requests.Auth) Requests.parse("auth", new Params(Json.obj("proof", Wire.TOKEN)));
        assertEquals(Wire.TOKEN, a.proof);
    }

    @Test
    public void targets() throws Exception {
        Requests.Target t = (Requests.Target) Requests.parse("message.mark_read", new Params(Json.obj("thread_id", 5L, "read", false)));
        assertTrue(t.isThread());
        assertFalse(t.read);
        t = (Requests.Target) Requests.parse("message.delete", new Params(Json.obj("kind", "mms", "id", 9L)));
        assertEquals("mms", t.kind);
        assertEquals(9L, t.id);
    }

    @Test
    public void restoreItems() throws Exception {
        Requests.RestoreSms s = Requests.restoreSms(Json.obj("kind", "sms", "address", "+1555", "date", 5L, "box", "sent", "body", "x"));
        assertEquals(2, s.type);
        assertNull(Requests.restoreSms(Json.obj("kind", "mms", "date", 5L, "box", "inbox")));
        assertNull(Requests.restoreSms(Json.obj("kind", "sms", "date", 5L, "box", "weird")));
        try {
            Requests.restoreSms(Json.obj("kind", "sms", "box", "inbox"));
            fail("no date");
        } catch (RequestException expected) {
            assertEquals(ErrorCode.BAD_REQUEST, expected.code);
        }
        try {
            Requests.restoreSms("x");
            fail("not an object");
        } catch (RequestException expected) {
            assertEquals(ErrorCode.BAD_REQUEST, expected.code);
        }
    }

    @Test
    public void boxNames() {
        for (int t = 1; t <= 6; t++) {
            assertEquals(t, Requests.smsType(Requests.boxName(t)));
        }
        assertEquals("other", Requests.boxName(0));
        assertEquals("other", Requests.boxName(7));
    }
}
