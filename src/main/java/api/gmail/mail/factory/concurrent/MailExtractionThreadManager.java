package api.gmail.mail.factory.concurrent;

import api.gmail.APIConnection;
import api.gmail.mail.factory.Mail;
import api.gmail.mail.factory.MailFactory;
import com.google.api.services.gmail.model.Message;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.logging.Logger;

/**
 * Class manages the {@link MailGetter} tasks in order to efficiently extract the contents
 * of messages.
 */
public class MailExtractionThreadManager {
    /**
     * List holding data extracted out of the {@link Message}
     */
    private ArrayList<Future<Mail>> extracted = new ArrayList<>();
    /**
     * The Messages.
     */
    private List<Message> messages;
    /**
     * The mail factory.
     */
    private MailFactory factory;
    /**
     * The Connection.
     */
    private APIConnection connection;

    private static final Logger logger = Logger.getLogger(MailExtractionThreadManager.class.getName());

    /**
     * Instantiates a new Mail extraction thread manager.
     *
     * @param mes           list of messages, straight from API (without contents)
     * @param con           reusing connection to the API
     * @param factoryObject the factory object
     */
    public MailExtractionThreadManager(List<Message> mes, APIConnection con, MailFactory factoryObject) {
        messages = mes;
        connection = con;
        factory = factoryObject;
    }

    /**
     * Extracts the contents out of the messages through the {@link java.util.concurrent.Callable}
     * {@link MailGetter} task.
     *
     * @return the list
     */
    public List<Mail> extraction() {
        ExecutorService exec = Executors.newCachedThreadPool();
        for(Message message : messages)
            extracted.add(exec.submit(new MailGetter(factory, connection, message)));
        return futureTranslation(exec);
    }

    /**
     * Simply gets the value out of the extracted List of {@link Future<Mail>} into
     * local {@link List<Mail>}, holding the contents.
     * */
    private List<Mail> futureTranslation(ExecutorService exe) {
        ArrayList<Mail> inbox = new ArrayList<>();
        for(Future<Mail> current : extracted) {
            try {
                inbox.add(current.get());
            } catch (InterruptedException | ExecutionException e) {
                logger.info(">> exception caught\n");
                e.printStackTrace();
            } finally {
                exe.shutdown();
            }
        }
        return inbox;
    }
}
