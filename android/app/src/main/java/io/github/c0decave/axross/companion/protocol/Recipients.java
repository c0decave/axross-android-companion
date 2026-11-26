package io.github.c0decave.axross.companion.protocol;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** The rules for an SMS recipient (docs/PROTOCOL.md, "sms.send"). */
public final class Recipients {
    public static final int MAX_RECIPIENTS = 10;
    public static final int MAX_INPUT_LENGTH = 64;
    public static final int MIN_DIGITS = 3;
    public static final int MAX_DIGITS = 20;

    private Recipients() {
    }

    /**
     * Removes spaces, '-', '(', ')', '.' and '/' and checks what is left: an
     * optional '+' and 3..20 ASCII digits. Returns null when it does not
     * pass.
     */
    public static String normalize(String input) {
        if (input == null || input.isEmpty() || input.length() > MAX_INPUT_LENGTH) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == ' ' || c == '-' || c == '(' || c == ')' || c == '.' || c == '/') {
                continue;
            }
            if (c == '+') {
                if (sb.length() != 0) {
                    return null;
                }
                sb.append(c);
            } else if (c >= '0' && c <= '9') {
                sb.append(c);
            } else {
                return null;
            }
        }
        int digits = sb.length() - (sb.length() > 0 && sb.charAt(0) == '+' ? 1 : 0);
        if (digits < MIN_DIGITS || digits > MAX_DIGITS) {
            return null;
        }
        return sb.toString();
    }

    /** The whole "recipients" list: its size, each entry, no duplicates. */
    public static List<String> parse(Params params) throws RequestException {
        int size = params.listSize("recipients");
        if (size > MAX_RECIPIENTS) {
            throw new RequestException(ErrorCode.TOO_MANY_RECIPIENTS,
                    "at most " + MAX_RECIPIENTS + " recipients", Json.obj("max", (long) MAX_RECIPIENTS));
        }
        List<Object> raw = params.reqList("recipients", 1, MAX_RECIPIENTS);
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Object o : raw) {
            if (!(o instanceof String)) {
                throw RequestException.bad("recipients", "a recipient must be a string");
            }
            String n = normalize((String) o);
            if (n == null) {
                // (which one is not said: the message must not carry numbers)
                throw RequestException.bad("recipients", "a recipient is not a phone number");
            }
            if (!seen.add(n)) {
                throw RequestException.bad("recipients", "a recipient is listed twice");
            }
            out.add(n);
        }
        return out;
    }
}
