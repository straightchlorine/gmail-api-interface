package api.gmail;

import api.gmail.exceptions.APIConnectionRuntimeException;
import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.util.StringUtils;
import com.google.api.client.util.store.FileDataStoreFactory;
import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.GmailScopes;
import com.google.api.services.gmail.model.ListMessagesResponse;
import com.google.api.services.gmail.model.Message;
import com.google.api.services.gmail.model.MessagePart;
import com.google.api.services.gmail.model.MessagePartHeader;
import org.apache.commons.codec.binary.Base64;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.security.GeneralSecurityException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Utility class providing connection to Gmail API services.
 * TODO: try to apply multithreading for both reading from the API and writing locally.
 */
public class APIConnection {

    /**
     * Class-wide logger.
     */
    private static final Logger logger = Logger.getLogger(APIConnection.class.getName());
    /**
     * Global HttpTransport object
     */
    private static final NetHttpTransport HTTP_TRANSPORT;
    /**
     * Application name.
     */
    private static final String APPLICATION_NAME = "api connection";
    /**
     * JSON factory.
     */
    private static final JsonFactory JSON_FACTORY = GsonFactory.getDefaultInstance();
    /**
     * Directory storing authentication tokens.
     */
    private static final String TOKENS_DIRECTORY_PATH = "tokens";
    /**
     * Global user name.
     */
    private static final String USER = "me";

    static {
        try {
            HTTP_TRANSPORT = GoogleNetHttpTransport.newTrustedTransport();
        } catch (GeneralSecurityException | IOException e) {
            throw new APIConnectionRuntimeException(e);
        }
    }

    /**
     * Class-wide Gmail API service.
     */
    private final Gmail service;

    /**
     * Constructor
     */
    public APIConnection() {
        try {
            service = getService();
        } catch (IOException e) {
            throw new APIConnectionRuntimeException(e);
        }
    }

    /**
     * Recursive algorithm for extracting the genuine parts of the message.
     *
     * @param handle reference to the list of parts from the payload
     * @return final set of parts
     */
    private static List<MessagePart> getPayloadParts(List<MessagePart> handle) {
        if (handle == null) return Collections.emptyList();
        if (handle.get(0).getBody().getData() != null) return handle;
        if (handle.get(0).getParts() != null) return getPayloadParts(handle.get(0).getParts());
        return Collections.emptyList();
    }

    /**
     * Helper method.
     * <p>
     * Decodes the MessagePartBody data into string.
     *
     * @param bodyPart coded body part
     * @return decoded string of the body part
     */
    private static String decode(String bodyPart) {
        return StringUtils.newStringUtf8(Base64.decodeBase64(bodyPart));
    }

    /**
     * Gmail API service object creation
     */
    private static Gmail getService() throws IOException {
        // new authorized api client service
        return new Gmail.Builder(HTTP_TRANSPORT, JSON_FACTORY, getCredentials()).setApplicationName(APPLICATION_NAME).build();
    }

    /**
     * Authentication method
     */
    private static Credential getCredentials() throws IOException {
        // loading the credentials to the program
        logger.log(Level.INFO, ">> loading credentials");
        InputStream in = APIConnection.class.getResourceAsStream("/credentials.json");
        if (in == null) throw new FileNotFoundException("Resource not found: " + "/credentials.json");
        // loading the credentials into client secrets
        GoogleClientSecrets clientSecrets = GoogleClientSecrets.load(JSON_FACTORY, new InputStreamReader(in));
        logger.log(Level.INFO, ">> loaded credentials into secrets");
        GoogleAuthorizationCodeFlow flow = new GoogleAuthorizationCodeFlow.Builder(APIConnection.HTTP_TRANSPORT, JSON_FACTORY, clientSecrets, Collections.singletonList(GmailScopes.MAIL_GOOGLE_COM)).setDataStoreFactory(new FileDataStoreFactory(new java.io.File(TOKENS_DIRECTORY_PATH))).setAccessType("offline").build();
        LocalServerReceiver receiver = new LocalServerReceiver.Builder().setPort(8888).build();
        return new AuthorizationCodeInstalledApp(flow, receiver).authorize("user");
    }

    /**
     * Helper method
     * <p>
     * Retrieve contents of the email from the api based on the ID of the particular message.
     *
     * @param message basic message object
     * @return handle on the contents, so extraction of each part of the message is easier
     */
    private Message getMessageHandle(Message message) {
        try {
            return service.users().messages().get(USER, message.getId()).setFormat("full").execute();
        } catch (IOException e) {
            throw new APIConnectionRuntimeException(e);
        }
    }

    /**
     * Method used to retrieve header list from message object.
     *
     * @param message basic message object
     * @return List of the headers
     */
    public Map<String, String> getSelectedHeaders(Message message) {
        Map<String, String> selectedHeaders = new HashMap<>();

        for (MessagePartHeader header : getMessageHandle(message).getPayload().getHeaders()) {
            if (selectedHeaders.size() == 5) break;
            if (header.getName().equals("Delivered-To|Date|From|To|Subject"))
                selectedHeaders.put(header.getName(), header.getValue());
        }
        return selectedHeaders;
    }

    /**
     * Helper method.
     * <p>
     * Extracts snippet of the message from the handle.
     *
     * @param message basic message object
     * @return String containing the snippet of the message
     */
    public String getSnippet(Message message) {
        Message handle = getMessageHandle(message);
        return handle.getSnippet();
    }

    /**
     * Helper method.
     * <p>
     * Extracts body of the message from the handle.
     *
     * @param message basic message object
     * @return String containing the body of the message
     */
    public String getBody(Message message) {
        return decode(getMessageHandle(message).getPayload().getBody().getData());
    }

    /**
     * Helper method separate to getBody, providing the parts, if available.
     * <p>
     * It uses the very same method, but returns raw MessagePart
     * instead of decoding the first element of the List.
     * <p>
     * Additionally, method reduces the needed data to bear minimum.
     *
     * @param message basic message object
     * @return List of MessageParts objects
     */
    public Map<Integer, String> getSelectedParts(Message message) {
        List<MessagePart> parts = getPayloadParts(getMessageHandle(message).getPayload().getParts());
        Map<Integer, String> data = new HashMap<>();
        if (parts != null) {
            for (MessagePart part : parts) {
                data.put(parts.indexOf(part), decode(part.getBody().getData()));
                data.put(parts.indexOf(part), part.getMimeType());
            }
        } else {
            data.put(0, getBody(message));
        }
        return data;
    }

    /**
     * Method that retrieves all the messages from the given page.
     *
     * @param token id of desired page
     * @return list of the messages from the page, specified by the token
     */
    public List<Message> getPage(String token) {
        try {
            Gmail.Users.Messages.List request = service.users().messages().list(USER).setPageToken(token);
            ListMessagesResponse response = request.execute();
            return response.getMessages();
        } catch (IOException e) {
            throw new APIConnectionRuntimeException(e);
        }
    }

    /**
     * Method retrieves 100 latest messages from the API.
     *
     * @return 100 newest mails
     */
    public List<Message> getPage() {
        try {
            Gmail.Users.Messages.List request = service.users().messages().list(USER);
            ListMessagesResponse response = request.execute();
            return response.getMessages();
        } catch (IOException e) {
            throw new APIConnectionRuntimeException(e);
        }
    }

    /**
     * Method works with Gmail API to retrieve all the messages.
     * <p>
     * It iterates over the whole inbox, going page by page and adds every single new message
     * into the list given as the first parameter.
     *
     * @param inbox empty List<Message> will hold all the data
     * @return a list of all the messages
     */
    public List<Message> getInbox(List<Message> inbox) {
        try {
            Gmail.Users.Messages.List request = service.users().messages().list(USER);
            ListMessagesResponse response = request.execute();

            // runs until all messages are available locally
            while (response.getMessages() != null) {
                inbox.addAll(response.getMessages());
                if (response.getNextPageToken() != null) {
                    request.setPageToken(response.getNextPageToken());
                    response = request.execute();
                } else {
                    break;
                }
            }
            return inbox;
        } catch (IOException e) {
            throw new APIConnectionRuntimeException(e);
        }
    }
}