package io.github.c0decave.axross.companion.protocol;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class FrameTest {
    @Test
    public void jsonFrameRoundTrip() throws Exception {
        byte[] bytes = Frame.json("{\"id\":1}");
        assertArrayEquals(new byte[] {0, 0, 0, 9, 1}, Arrays.copyOf(bytes, 5));
        Frame f = Frame.read(new ByteArrayInputStream(bytes), 100);
        assertEquals(Frame.KIND_JSON, f.kind);
        assertEquals("{\"id\":1}", f.text());
    }

    @Test
    public void cleanEndIsNull() throws Exception {
        assertNull(Frame.read(new ByteArrayInputStream(new byte[0]), 100));
    }

    @Test
    public void oversizedJsonIsSkippedNotFatal() throws Exception {
        byte[] big = Frame.json("\"" + new String(new char[200]).replace('\0', 'x') + "\"");
        byte[] next = Frame.json("{}");
        ByteArrayInputStream in = new ByteArrayInputStream(Wire.concat(big, next));
        Frame f = Frame.read(in, 100);
        assertTrue(f.skipped());
        assertEquals("{}", Frame.read(in, 100).text());
    }

    @Test
    public void blobsInChunks() throws Exception {
        byte[] data = new byte[Frame.CHUNK_DATA * 2 + 7];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) i;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Frame.writeBlob(out, 7, data);
        ByteArrayInputStream in = new ByteArrayInputStream(out.toByteArray());
        ByteArrayOutputStream got = new ByteArrayOutputStream();
        int chunks = 0;
        while (true) {
            Frame f = Frame.read(in, 0);
            if (f == null) {
                break;
            }
            chunks++;
            assertEquals(Frame.KIND_BLOB, f.kind);
            assertEquals(7, f.blobId());
            got.write(f.payload, Frame.BLOB_HEADER, f.length - Frame.BLOB_HEADER);
            assertEquals(chunks == 3, (f.blobFlags() & Frame.FLAG_LAST) != 0);
        }
        assertEquals(3, chunks);
        assertArrayEquals(data, got.toByteArray());
    }

    @Test
    public void emptyBlobIsOneLastChunk() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Frame.writeBlob(out, 1, new byte[0]);
        Frame f = Frame.read(new ByteArrayInputStream(out.toByteArray()), 0);
        assertEquals(Frame.BLOB_HEADER, f.length);
        assertEquals(Frame.FLAG_LAST, f.blobFlags());
    }

    @Test
    public void maxLengthIsAccepted() throws Exception {
        // (the length field itself: 16 MiB is valid; the payload need not be read here)
        byte[] head = {1, 0, 0, 0, 2, 0, 0, 0, 1, 1};
        byte[] all = Arrays.copyOf(head, 4 + Frame.MAX_LENGTH);
        Frame f = Frame.read(new ByteArrayInputStream(all), 0);
        assertEquals(Frame.MAX_LENGTH - 1, f.length);
    }

    /** protocol/fixtures/frames.json, the shared definition. */
    @Test
    @SuppressWarnings("unchecked")
    public void sharedFixtures() throws Exception {
        Map<String, Object> doc = Wire.fixture("fixtures/frames.json");
        List<Object> frames = (List<Object>) doc.get("frames");
        assertTrue(frames.size() >= 10);
        for (Object o : frames) {
            Map<String, Object> c = (Map<String, Object>) o;
            String name = (String) c.get("name");
            ByteArrayInputStream in = new ByteArrayInputStream(Wire.hex((String) c.get("hex")));
            if (c.containsKey("error")) {
                try {
                    Frame.read(in, Protocol.MAX_REQUEST_JSON);
                    fail(name + ": no error");
                } catch (ProtocolException expected) {
                    // (as it should be)
                }
                continue;
            }
            Frame f = Frame.read(in, Protocol.MAX_REQUEST_JSON);
            assertEquals(name, -1, in.read());
            if ("json".equals(c.get("kind"))) {
                assertEquals(name, Frame.KIND_JSON, f.kind);
                assertEquals(name, c.get("json"), Json.parse(f.text()));
            } else {
                assertEquals(name, Frame.KIND_BLOB, f.kind);
                assertEquals(name, c.get("blob"), f.blobId());
                assertEquals(name, c.get("last"), (f.blobFlags() & Frame.FLAG_LAST) != 0);
                assertArrayEquals(name, Wire.hex((String) c.get("data_hex")),
                        Arrays.copyOfRange(f.payload, Frame.BLOB_HEADER, f.length));
            }
        }
    }
}
