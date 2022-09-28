package api.gmail;

import com.google.api.services.gmail.model.Message;
import org.junit.Ignore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Tests for the {@link APIConnection} class.
 */
class APIConnectionTest {

    /**
     * Class-wide inbox.
     */
    List<Message> inbox;
    APIConnection con;

    /**
     * Getting 100 newest messages from API into the inbox.
     */
    @BeforeEach
    void setup() {
        con = new APIConnection();
        inbox = con.getPage();
    }

    /**
     * Checks whether every message has a body.
     * <p>
     * Needed because different messages often have different structures.
     */
    @Ignore
    @Test
    @DisplayName("message body retrieval")
    void testBodyRetrieval() {
        for (Message message : inbox) {
            assertNotEquals(null, con.getSelectedParts(message));
        }
    }
}