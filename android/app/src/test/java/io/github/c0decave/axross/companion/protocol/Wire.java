package io.github.c0decave.axross.companion.protocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/** A session on one end of a loopback connection, the test on the other. */
final class Wire implements AutoCloseable {
    static final Session.Log QUIET = new Session.Log() {
        @Override
        public void info(String line) {
        }

        @Override
        public void warn(String line) {
        }
    };

    final Socket client;
    final Socket server;
    final InputStream in;
    final OutputStream out;
    final ScheduledExecutorService pool = Executors.newScheduledThreadPool(4);
    Session session;
    Thread reader;

    Wire() throws IOException {
        try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            client = new Socket(InetAddress.getLoopbackAddress(), listener.getLocalPort());
            server = listener.accept();
        }
        client.setSoTimeout(15_000);
        in = client.getInputStream();
        out = client.getOutputStream();
    }

    Session start(Session.Handler handler) {
        session = new Session(new Session.Transport() {
            @Override
            public InputStream input() throws IOException {
                return server.getInputStream();
            }

            @Override
            public OutputStream output() throws IOException {
                return server.getOutputStream();
            }

            @Override
            public void close() {
                try {
                    server.close();
                } catch (IOException ignored) {
                    // (closed)
                }
            }
        }, Protocol.UID_SHELL, handler, pool, pool, QUIET);
        reader = new Thread(new Runnable() {
            @Override
            public void run() {
                session.run();
            }
        });
        reader.start();
        return session;
    }

    void send(Map<String, Object> request) throws IOException {
        sendText(Json.write(request));
    }

    void sendText(String text) throws IOException {
        out.write(Frame.json(text));
        out.flush();
    }

    void sendRaw(byte[] bytes) throws IOException {
        out.write(bytes);
        out.flush();
    }

    Frame next() throws IOException, ProtocolException {
        Frame f = Frame.read(in, Frame.MAX_LENGTH);
        assertNotNull("the companion closed the connection", f);
        return f;
    }

    Map<String, Object> nextJson() throws Exception {
        Frame f = next();
        assertEquals("a JSON frame", Frame.KIND_JSON, f.kind);
        return Json.parseObject(f.text());
    }

    /** True when the companion closes (EOF) within the timeout. */
    boolean closedByCompanion() throws IOException {
        try {
            return in.read() < 0;
        } catch (java.net.SocketException e) {
            return true;
        }
    }

    @Override
    public void close() throws IOException {
        client.close();
        server.close();
        pool.shutdownNow();
    }

    static Map<String, Object> hello() {
        return Json.obj("id", 1L, "method", "hello", "params", Json.obj("protocols", java.util.Arrays.asList(1L)));
    }

    static File protocolDir() {
        String dir = System.getProperty("protocol.dir");
        if (dir == null) {
            dir = "../../protocol";
        }
        return new File(dir);
    }

    static Map<String, Object> fixture(String path) throws Exception {
        byte[] bytes = Files.readAllBytes(new File(protocolDir(), path).toPath());
        return Json.parseObject(new String(bytes, StandardCharsets.UTF_8));
    }

    static byte[] hex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        }
        return b;
    }

    static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            b.write(p, 0, p.length);
        }
        return b.toByteArray();
    }
}
