package api.gmail;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.api.services.gmail.model.Message;
import com.google.api.services.gmail.model.MessagePart;
import com.google.api.services.gmail.model.MessagePartBody;
import com.google.api.services.gmail.model.MessagePartHeader;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class MailTest {
    static MessagePartHeader h(String name, String value) {
        return new MessagePartHeader().setName(name).setValue(value);
    }

    static MessagePart leaf(String mime, String filename, String text, MessagePartHeader... headers) {
        return new MessagePart().setMimeType(mime).setFilename(filename).setHeaders(List.of(headers))
                .setBody(new MessagePartBody().encodeData(text.getBytes(UTF_8)));
    }

    static MessagePart multi(String mime, MessagePart... parts) {
        return new MessagePart().setMimeType(mime).setParts(List.of(parts));
    }

    static Message msg(MessagePart payload) {
        return new Message().setId("18c0ffee").setInternalDate(0L).setPayload(payload);
    }

    // 2022: equals("Delivered-To|Date|From|To|Subject") never matched, so every header came back empty
    @Test
    void headersMatchCaseInsensitivelyAmongOthers() {
        var m = msg(new MessagePart().setHeaders(List.of(
                h("Received", "x"), h("FROM", "Ann <ann@example.com>"), h("DKIM-Signature", "y"), h("subject", "Hi"))));
        assertEquals(new Mail("18c0ffee", Instant.EPOCH, "Ann <ann@example.com>", "Hi", false), Mail.of(m));
    }

    @Test
    void missingSubjectAndNullsAreSafe() {
        var m = new Message().setId("x").setPayload(new MessagePart());  // no headers, no internalDate
        assertEquals(new Mail("x", Instant.EPOCH, "", "(no subject)", false), Mail.of(m));
    }

    @Test
    void lineFormatsUtcTruncatesFromAndStripsControls() {
        var m = msg(new MessagePart().setHeaders(List.of(
                h("From", "A".repeat(23) + "\uD83D\uDE42tail"), h("Subject", "a\u001b[31mb\u009bc\u202Ed\u200Fe"))))
                .setInternalDate(1_700_000_000_000L);
        assertEquals("18c0ffee  2023-11-14 22:13  " + "A".repeat(23) + "\uD83D\uDE42  a [31mb c d e",
                Mail.of(m).line(ZoneOffset.UTC, false));
    }

    @Test
    void lineShowsDisplayNameAndColoursUnreadBold() {
        var m = msg(new MessagePart().setHeaders(List.of(h("From", "\"Nowak, Anna\" <anna@example.com>"), h("Subject", "Hi"))))
                .setLabelIds(List.of("INBOX", "UNREAD"));
        assertEquals("18c0ffee  1970-01-01 00:00  Nowak, Anna" + " ".repeat(13) + "  Hi", Mail.of(m).line(ZoneOffset.UTC, false));
        assertEquals("\033[2m18c0ffee\033[0m  \033[36m1970-01-01 00:00\033[0m  \033[1;33mNowak, Anna" + " ".repeat(13)
                + "\033[0m  \033[1mHi\033[0m", Mail.of(m).line(ZoneOffset.UTC, true));
    }

    @Test
    void displayNameFallsBackToTheAddressWhenThereIsNoName() {
        assertEquals("anna@example.com", Mail.displayName("<anna@example.com>"));
        assertEquals("anna@example.com", Mail.displayName("anna@example.com"));
    }

    @Test
    void showBoldsSubjectAndDimsQuotedLinesOnlyInColour() {
        var m = msg(leaf("text/plain", "", "Sure.\n> Lunch?", h("From", "Ann <ann@example.com>"), h("Subject", "Re: Lunch")));
        assertEquals("From: Ann <ann@example.com>\nTo: \nDate: \nSubject: Re: Lunch\n\nSure.\n> Lunch?\n", Mail.show(m, false));
        assertEquals("\033[2mFrom:\033[0m Ann <ann@example.com>\n\033[2mTo:\033[0m \n\033[2mDate:\033[0m \n"
                + "\033[2mSubject:\033[0m \033[1mRe: Lunch\033[0m\n\nSure.\n\033[2m> Lunch?\033[0m\n", Mail.show(m, true));
    }

    // 2022: a single-part message (no parts list) came back with an empty body.
    // Also pins: UTF-8 decode despite a stale charset label, CRLF -> LF, controls stripped, \t and ZWJ kept.
    @Test
    void singlePartPlainTextAtRoot() {
        var p = leaf("text/plain", null, "Zażółć 👨\u200D👩\u200D👧\r\n\u001b[31mx\u009by\tz",
                h("Content-Type", "text/plain; charset=iso-8859-2"));
        assertEquals("Zażółć 👨\u200D👩\u200D👧\n [31mx y\tz", Mail.body(msg(p)));
    }

    // 2022: each part's decoded text was overwritten by its MIME type
    @Test
    void alternativePrefersPlainOverHtml() {
        var p = multi("multipart/alternative", leaf("text/plain", "", "BODY"), leaf("text/html", "", "<p>X</p>"));
        assertEquals("BODY", Mail.body(msg(p)));
    }

    // 2022: only the first part was examined
    @Test
    void nestedMixedKeepsBodyAndFooterSkipsAttachmentsAndAttachedMail() {
        var p = multi("multipart/mixed",
                multi("multipart/alternative", leaf("text/plain", "", "BODY"), leaf("text/html", "", "<p>X</p>")),
                leaf("text/plain", "", "-- footer"),
                leaf("text/plain", "log.txt", "LOG"),
                leaf("text/plain", "", "ATT", h("Content-Disposition", "attachment")),
                new MessagePart().setMimeType("message/rfc822").setFilename("f.eml").setParts(List.of(
                        multi("multipart/alternative", leaf("text/plain", "", "FWD"), leaf("text/html", "", "<p>F</p>")))));
        assertEquals("BODY\n\n-- footer", Mail.body(msg(p)));
    }

    @Test
    void htmlOnlyFallsBackToUnescapedSnippet() {
        var p = multi("multipart/alternative", leaf("text/plain", "", " \r\n"), leaf("text/html", "", "<p>Don't</p>"));
        var m = msg(p).setSnippet("Don&#39;t &lt;b&gt; &amp;amp; go");
        assertEquals("Don't <b> &amp; go\n[no plain-text part; showing snippet]", Mail.body(m));
    }

    @Test
    void outOfLineTextPartShowsPlaceholder() {
        var big = new MessagePart().setMimeType("text/plain").setFilename("")
                .setBody(new MessagePartBody().setAttachmentId("ANGj").setSize(3_000_000));
        var p = multi("multipart/mixed", big, leaf("text/plain", "", "-- footer"));
        assertEquals("[text part too large to show here; open it in Gmail]\n\n-- footer", Mail.body(msg(p)));
    }
}
