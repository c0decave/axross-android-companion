package io.github.c0decave.axross.companion.protocol;

/** A broken stream: the session cannot go on (reason "protocol_error"). */
public final class ProtocolException extends Exception {
    public ProtocolException(String message) {
        super(message);
    }
}
