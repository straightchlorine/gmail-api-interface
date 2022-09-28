package api.gmail.mail.body;

import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Part of Mail class.
 * <p>
 * Meant to contain and later process the body of the mail.
 */
public class Body {
    /**
     * Contains snippet of the message(possibly will be deleted).
     */
    private final String snippet;
    /**
     * Contains the MessageParts at the bottom of the payload.
     */
    private final Map<Integer, String> parts;
    /**
     * Contains type of the body(possibly will be resolved differently).
     */
    private String type;

    /**
     * Constructor, simple assignment.
     *
     * @param snippet String
     * @param parts   Map<Integer, String>, integer is the id of the part and String variable contains necessary data.
     */
    public Body(String snippet, Map<Integer, String> parts) {
        this.snippet = snippet;
        this.parts = parts;
    }
}