package io.github.c0decave.axross.companion.protocol;

/** The rules for an SMS text (docs/PROTOCOL.md, "sms.send"). */
public final class TextRules {
    public static final int MAX_TEXT_CHARS = 5000;
    public static final int MAX_PARTS = 30;

    private TextRules() {
    }

    public static String check(Params params) throws RequestException {
        if (params.has("text") && params.raw().get("text") instanceof String
                && ((String) params.raw().get("text")).length() > MAX_TEXT_CHARS) {
            throw new RequestException(ErrorCode.TOO_LARGE, "text longer than " + MAX_TEXT_CHARS,
                    Json.obj("max", (long) MAX_TEXT_CHARS));
        }
        String text = params.reqString("text", 1, MAX_TEXT_CHARS);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == 0) {
                throw RequestException.bad("text", "text contains NUL");
            }
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(i + 1))) {
                    throw RequestException.bad("text", "text contains an unpaired surrogate");
                }
                i++;
            } else if (Character.isLowSurrogate(c)) {
                throw RequestException.bad("text", "text contains an unpaired surrogate");
            }
        }
        return text;
    }

    /** The number of parts Android made of the text, checked. */
    public static void checkParts(int parts) throws RequestException {
        if (parts > MAX_PARTS) {
            throw new RequestException(ErrorCode.TOO_LARGE, "more than " + MAX_PARTS + " parts",
                    Json.obj("max", (long) MAX_PARTS));
        }
    }
}
