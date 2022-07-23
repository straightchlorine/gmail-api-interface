package api.gmail.mail.headers;

import java.util.Map;

/**
 * Class contains selected headers of the message.
 */
public class Headers {

    /**
     * Map holding the data from the API.
     * <p>
     * Map<HeaderName, HeaderValue>
     */
    Map<String, String> headers;

    /**
     * Constructor
     *
     * @param headers processed data from the API
     */
    public Headers(Map<String, String> headers) {
        this.headers = headers;
    }
}