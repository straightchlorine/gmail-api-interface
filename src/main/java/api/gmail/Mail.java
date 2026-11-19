package api.gmail;

import static api.gmail.Terminal.*;

import com.google.api.services.gmail.model.Message;
import com.google.api.services.gmail.model.MessagePart;
import com.google.api.services.gmail.model.MessagePartBody;
import com.google.api.services.gmail.model.MessagePartHeader;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A Gmail message as this tool prints it: one {@link #line} per message for list, {@link #show} for
 * read.
 */
record Mail(String id, Instant date, String from, String subject, boolean unread) {

    /** The headers {@link #of} reads; GmailClient.list asks Gmail for exactly these. */
    static final List<String> HEADERS = List.of("From", "Subject");

    private static final int FROM_WIDTH = 24;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    // possessive leading \s*+: a From that starts with thousands of spaces would otherwise
    // backtrack for minutes
    private static final Pattern ADDRESS =
            Pattern.compile("\\s*+\"?(?<name>.*?)\"?\\s*<(?<address>[^>]*)>\\s*");

    static Mail of(Message m) {
        Instant date = Instant.ofEpochMilli(Objects.requireNonNullElse(m.getInternalDate(), 0L));
        String from = header(m.getPayload(), "From");

        String subject = header(m.getPayload(), "Subject");
        if (subject.isBlank()) subject = "(no subject)";

        boolean unread = m.getLabelIds() != null && m.getLabelIds().contains("UNREAD");
        return new Mail(m.getId(), date, from, subject, unread);
    }

    String line(ZoneId zone, boolean color) {
        return paint(color, DIM, id)
                + "  "
                + paint(color, CYAN, DATE.format(date.atZone(zone)))
                + "  "
                + paint(color, unread ? BOLD_YELLOW : YELLOW, fit(displayName(from), FROM_WIDTH))
                + "  "
                + paint(color, unread ? BOLD : NONE, subject);
    }

    /** Headers, a blank line and the body, as printed by {@code read}. */
    static String show(Message m, boolean color) {
        var s = new StringBuilder();
        for (String name : List.of("From", "To", "Date", "Subject")) {
            String label = paint(color, DIM, name + ":");
            String value =
                    paint(
                            color,
                            name.equals("Subject") ? BOLD : NONE,
                            header(m.getPayload(), name));
            s.append(label).append(' ').append(value).append('\n');
        }
        s.append('\n');
        for (String bodyLine : body(m).split("\n", -1)) {
            boolean quoted = bodyLine.startsWith(">");
            s.append(paint(color, quoted ? DIM : NONE, bodyLine)).append('\n');
        }
        return s.toString();
    }

    /** "Anna Nowak" <anna@example.com> -> Anna Nowak; a bare address stays as it is. */
    static String displayName(String from) {
        if (from.length() > 1000) return from; // ADDRESS's lazy name group is quadratic in length
        var m = ADDRESS.matcher(from);
        if (!m.matches()) return from;
        return m.group("name").isBlank() ? m.group("address") : m.group("name");
    }

    /**
     * Cuts s to width code points, so no surrogate pair is split (a flag or ZWJ emoji at the edge
     * still can be), then pads it like %-{width}s (UTF-16 chars).
     */
    private static String fit(String s, int width) {
        String cut =
                s.codePointCount(0, s.length()) > width
                        ? s.substring(0, s.offsetByCodePoints(0, width))
                        : s;
        return String.format("%-" + width + "s", cut);
    }

    private static String header(MessagePart p, String name) {
        if (p == null || p.getHeaders() == null) return "";
        for (MessagePartHeader h : p.getHeaders()) {
            if (name.equalsIgnoreCase(h.getName()))
                return clean(Objects.toString(h.getValue(), ""));
        }
        return "";
    }

    /** Every non-attachment text/plain part, or the snippet when there is none. */
    static String body(Message m) {
        List<String> parts = new ArrayList<>();
        collectPlainText(m.getPayload(), parts);
        parts.removeIf(String::isBlank); // whitespace-only text/plain beside an HTML body counts as
        // HTML-only
        String text =
                parts.isEmpty()
                        ? unescape(Objects.toString(m.getSnippet(), ""))
                                + "\n[no plain-text part; showing snippet]"
                        : String.join("\n\n", parts);
        return cleanMultiline(text);
    }

    private static void collectPlainText(MessagePart p, List<String> out) {
        if (p == null) return;
        String mime = Objects.toString(p.getMimeType(), "").toLowerCase(Locale.ROOT);
        if (mime.startsWith(
                "multipart/")) { // never descend into message/rfc822: attached mail is not body
            if (p.getParts() != null) {
                for (MessagePart child : p.getParts()) {
                    collectPlainText(child, out);
                }
            }
            return;
        }
        MessagePartBody body = p.getBody();
        if (!mime.equals("text/plain") || isAttachment(p) || body == null) return;
        // Gmail transcodes inline text data to UTF-8; the part's charset= label is stale (SO
        // 59663637)
        if (body.getData() != null) {
            out.add(new String(body.decodeData(), StandardCharsets.UTF_8));
        } else if (body.getAttachmentId() != null) {
            out.add("[text part too large to show here; open it in Gmail]");
        }
    }

    private static boolean isAttachment(MessagePart p) {
        return (p.getFilename() != null && !p.getFilename().isEmpty())
                || header(p, "Content-Disposition")
                        .toLowerCase(Locale.ROOT)
                        .startsWith("attachment");
    }

    private static String unescape(String s) { // snippets are HTML-escaped
        return s.replace("&#39;", "'")
                .replace("&quot;", "\"")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&");
    }
}
