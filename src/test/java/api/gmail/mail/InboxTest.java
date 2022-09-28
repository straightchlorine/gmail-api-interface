package api.gmail.mail;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Tests for {@link Inbox} class.
 */
class InboxTest {

    /**
     * Instance of {@link Inbox} class.
     */
    Inbox inbox;

    /**
     * Setting up the test.
     */
    @BeforeEach
    void setup() {
        inbox = new Inbox();
    }

    /**
     * Checks whether the inbox is filled correctly.
     */
    @Test
    @DisplayName("creating local inbox")
    void testLocalInbox() {
        assertNotEquals(null, inbox.getInboxList());
    }
}