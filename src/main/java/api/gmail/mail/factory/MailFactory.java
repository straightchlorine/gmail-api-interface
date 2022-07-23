package api.gmail.mail.factory;

import api.gmail.APIConnection;
import api.gmail.mail.body.Body;
import api.gmail.mail.headers.Headers;
import com.google.api.services.gmail.model.Message;

/**
 * Class responsible for Mail object creation.
 */
public class MailFactory {
    /**
     * Helper method - returns an instance of itself.
     *
     * @return MailFactory object
     */
    public static MailFactory getMailFactory() {
        return new MailFactory();
    }

    /**
     * Method responsible for creating and filling the Mail object with data.
     *
     * @param message    Message object from the API
     * @param connection APIConnection instance
     * @return new Mail instance, containing data from message extracted via connection object.
     */
    public Mail getMail(Message message, APIConnection connection) {
        Headers headers = new Headers(connection.getSelectedHeaders(message));
        Body body = new Body(connection.getSnippet(message), connection.getSelectedParts(message));
        return new Mail(message.getId(), headers, body);
    }
}