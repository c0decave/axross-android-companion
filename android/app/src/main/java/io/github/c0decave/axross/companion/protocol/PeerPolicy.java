package io.github.c0decave.axross.companion.protocol;

import java.io.IOException;

/**
 * Who may connect: only uid 2000 (shell - adbd, i.e. a computer through
 * ADB) and uid 0 (root). The credentials come from the socket
 * ({@code LocalSocket.getPeerCredentials()} on the phone), behind
 * {@link Source} so that the rule can be tested without one.
 */
public final class PeerPolicy {
    /** Where the peer's uid comes from. */
    public interface Source {
        /** The peer's uid; throws when the socket cannot say. */
        int peerUid() throws IOException;
    }

    private PeerPolicy() {
    }

    public static boolean allowed(int uid) {
        return uid == Protocol.UID_SHELL || uid == Protocol.UID_ROOT;
    }

    /**
     * Admits a peer or not. A source that fails is refused (never "allow on
     * error").
     *
     * @return the peer's uid when admitted, or -1
     */
    public static int admit(Source source) {
        int uid;
        try {
            uid = source.peerUid();
        } catch (IOException | RuntimeException e) {
            return -1;
        }
        return allowed(uid) ? uid : -1;
    }
}
