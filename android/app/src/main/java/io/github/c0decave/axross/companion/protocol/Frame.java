package io.github.c0decave.axross.companion.protocol;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * The framing (docs/PROTOCOL.md, section 2): a u32 big-endian length that
 * counts the kind byte and the payload, the kind byte, the payload.
 */
public final class Frame {
    public static final int KIND_JSON = 0x01;
    public static final int KIND_BLOB = 0x02;

    /** The largest length a frame may have (the kind byte and the payload). */
    public static final int MAX_LENGTH = 16 * 1024 * 1024;
    /** A blob chunk's header: the blob id (u32) and the flags (u8). */
    public static final int BLOB_HEADER = 5;
    public static final int FLAG_LAST = 0x01;
    /** How much data a sender puts into one chunk. */
    public static final int CHUNK_DATA = 1024 * 1024;

    public final int kind;
    /** The payload; null for a JSON frame that was too large (skipped). */
    public final byte[] payload;
    /** The payload's length (also when it was skipped). */
    public final int length;

    private Frame(int kind, byte[] payload, int length) {
        this.kind = kind;
        this.payload = payload;
        this.length = length;
    }

    public boolean skipped() {
        return payload == null;
    }

    /** The JSON frame's text. */
    public String text() {
        return new String(payload, StandardCharsets.UTF_8);
    }

    /** A blob chunk's id. */
    public long blobId() {
        return readU32(payload, 0);
    }

    public int blobFlags() {
        return payload[4] & 0xff;
    }

    /**
     * Reads one frame. Returns null at a clean end of the stream (before the
     * first byte of a frame). A JSON frame whose payload is longer than
     * {@code maxJson} is read past and returned with a null payload.
     *
     * @throws ProtocolException a broken stream: a bad length or kind, a
     *     malformed blob chunk, an end inside a frame
     */
    public static Frame read(InputStream in, int maxJson) throws IOException, ProtocolException {
        byte[] head = new byte[4];
        int first = in.read();
        if (first < 0) {
            return null;
        }
        head[0] = (byte) first;
        readFully(in, head, 1, 3);
        long length = readU32(head, 0);
        if (length < 1 || length > MAX_LENGTH) {
            throw new ProtocolException("frame length " + length + " out of range");
        }
        int kind = in.read();
        if (kind < 0) {
            throw new ProtocolException("end of stream inside a frame");
        }
        int payloadLength = (int) length - 1;
        if (kind == KIND_JSON) {
            if (payloadLength > maxJson) {
                skipFully(in, payloadLength);
                return new Frame(kind, null, payloadLength);
            }
        } else if (kind == KIND_BLOB) {
            if (payloadLength < BLOB_HEADER) {
                throw new ProtocolException("blob chunk shorter than its header");
            }
        } else {
            throw new ProtocolException("unknown frame kind " + kind);
        }
        byte[] payload = new byte[payloadLength];
        readFully(in, payload, 0, payloadLength);
        Frame frame = new Frame(kind, payload, payloadLength);
        if (kind == KIND_BLOB) {
            if (frame.blobId() == 0) {
                throw new ProtocolException("blob id 0");
            }
            if ((frame.blobFlags() & ~FLAG_LAST) != 0) {
                throw new ProtocolException("unknown blob flags");
            }
        }
        return frame;
    }

    /** One JSON frame, as bytes. */
    public static byte[] json(String text) {
        byte[] utf8 = text.getBytes(StandardCharsets.UTF_8);
        if (utf8.length + 1 > MAX_LENGTH) {
            throw new IllegalArgumentException("JSON frame too large");
        }
        byte[] out = new byte[5 + utf8.length];
        writeU32(out, 0, utf8.length + 1L);
        out[4] = KIND_JSON;
        System.arraycopy(utf8, 0, out, 5, utf8.length);
        return out;
    }

    /** One blob chunk, as bytes. */
    public static byte[] blobChunk(long blobId, boolean last, byte[] data, int offset, int count) {
        if (blobId < 1 || blobId > 0xffffffffL) {
            throw new IllegalArgumentException("blob id out of range");
        }
        if (count + 1 + BLOB_HEADER > MAX_LENGTH) {
            throw new IllegalArgumentException("chunk too large");
        }
        byte[] out = new byte[5 + BLOB_HEADER + count];
        writeU32(out, 0, 1L + BLOB_HEADER + count);
        out[4] = KIND_BLOB;
        writeU32(out, 5, blobId);
        out[9] = (byte) (last ? FLAG_LAST : 0);
        System.arraycopy(data, offset, out, 10, count);
        return out;
    }

    /** Writes a whole blob as chunks of at most {@link #CHUNK_DATA} bytes. */
    public static void writeBlob(OutputStream out, long blobId, byte[] data) throws IOException {
        int offset = 0;
        do {
            int count = Math.min(CHUNK_DATA, data.length - offset);
            boolean last = offset + count >= data.length;
            out.write(blobChunk(blobId, last, data, offset, count));
            offset += count;
        } while (offset < data.length);
    }

    static long readU32(byte[] b, int at) {
        return ((b[at] & 0xffL) << 24) | ((b[at + 1] & 0xffL) << 16) | ((b[at + 2] & 0xffL) << 8) | (b[at + 3] & 0xffL);
    }

    static void writeU32(byte[] b, int at, long v) {
        b[at] = (byte) (v >>> 24);
        b[at + 1] = (byte) (v >>> 16);
        b[at + 2] = (byte) (v >>> 8);
        b[at + 3] = (byte) v;
    }

    private static void readFully(InputStream in, byte[] b, int off, int len) throws IOException, ProtocolException {
        while (len > 0) {
            int n = in.read(b, off, len);
            if (n < 0) {
                throw new ProtocolException("end of stream inside a frame");
            }
            off += n;
            len -= n;
        }
    }

    private static void skipFully(InputStream in, int len) throws IOException, ProtocolException {
        byte[] sink = new byte[Math.min(len, 64 * 1024)];
        while (len > 0) {
            int n = in.read(sink, 0, Math.min(len, sink.length));
            if (n < 0) {
                throw new ProtocolException("end of stream inside a frame");
            }
            len -= n;
        }
    }
}
