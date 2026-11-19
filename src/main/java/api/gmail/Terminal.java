package api.gmail;

import java.util.regex.Pattern;

/**
 * What may reach the terminal. Text from strangers (headers, bodies, error messages) goes through a clean method,
 * so the escapes {@link #paint} and {@link #link} add are the only ones printed.
 */
class Terminal {
    static final String NONE = "", BOLD = "1", DIM = "2", YELLOW = "33", CYAN = "36", BOLD_YELLOW = "1;33";

    // C0/C1 controls (ESC, CSI, CR, DEL) and all bidi controls; not \p{Cf}, which would break ZWJ emoji
    private static final String UNSAFE = "\\p{Cc}\\u061C\\u200E\\u200F\\u202A-\\u202E\\u2066-\\u2069";
    private static final Pattern UNSAFE_CHAR = Pattern.compile("[" + UNSAFE + "]");
    private static final Pattern UNSAFE_CHAR_BUT_NEWLINE_OR_TAB = Pattern.compile("[[" + UNSAFE + "]&&[^\\n\\t]]");

    /** Replaces every control and bidi character, line breaks included, with a space. */
    static String clean(String s) {
        return UNSAFE_CHAR.matcher(s).replaceAll(" ");
    }

    /** Like {@link #clean}, but keeps line breaks and tabs; CRLF becomes LF. */
    static String cleanMultiline(String s) {
        return UNSAFE_CHAR_BUT_NEWLINE_OR_TAB.matcher(s.replace("\r\n", "\n")).replaceAll(" ");
    }

    /**
     * Makes url clickable as a whole (an OSC 8 hyperlink) when on. A terminal otherwise finds a bare
     * URL by its text, which stops working once the URL wraps over several lines inside tmux.
     */
    static String link(boolean on, String url) {
        return on ? "\033]8;;" + url + "\033\\" + url + "\033]8;;\033\\" : url;
    }

    /** Wraps s in an ANSI SGR style when on; {@link #NONE} leaves it bare. */
    static String paint(boolean on, String style, String s) {
        return on && !style.isEmpty() ? "\033[" + style + "m" + s + "\033[0m" : s;
    }
}
