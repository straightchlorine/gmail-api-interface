package api.gmail.mail.factory.concurrent;

import api.gmail.APIConnection;
import api.gmail.mail.factory.Mail;
import api.gmail.mail.factory.MailFactory;
import com.google.api.services.gmail.model.Message;

import java.util.concurrent.Callable;

/**
 * Callable task, which returns local and compact {@link Mail} object as opposed
 * to large {@link Message} object.
 */
public class MailGetter implements Callable<Mail> {
    /**
     * Factory object.
     */
    MailFactory factory;
    /**
     * Api connection.
     */
    APIConnection connection;

    /**
     * The message (meant to be processed).
     */
    Message message;

    /**
     * Instantiates a new Mail getter.
     *
     * @param factoryObject factory object
     * @param con           connection to api
     * @param mes           message
     */
    MailGetter(MailFactory factoryObject, APIConnection con, Message mes ) {
        factory = factoryObject;
        connection = con;
        message = mes;
    }

    @Override
    public Mail call() {
        return factory.getMail(message, connection);
    }
}
