package api.gmail.mail;

import api.gmail.APIConnection;
import api.gmail.mail.factory.Mail;
import api.gmail.mail.factory.MailFactory;
import com.google.api.services.gmail.model.Message;

import java.util.ArrayList;
import java.util.List;

/**
 * Class responsible for creating local inbox.
 * Uses the MailFactory object to create List of Mail objects.
 */
public class Inbox {
    /**
     * Connection object, required to retrieve data from the API.
     */
    APIConnection con;

    /**
     * List of Message objects meant to be retrieved form the API and turned into local inbox.
     */
    List<Message> messages;

    /**
     * Factory object, creates instances of Mail class.
     */
    MailFactory factory = MailFactory.getMailFactory();

    /**
     * Final inbox - List of Mail objects.
     */
    List<Mail> inboxList = new ArrayList<>();

    /**
     * Constructor; setting up the connection and creating initial inbox.
     */
    public Inbox() {
        con = new APIConnection();
        messages = con.getPage();
        for (Message message : messages) {
            inboxList.add(factory.getMail(message, con));

        }
    }

    /**
     * Inbox getter  method.
     *
     * @return list of Mail objects
     */
    public List<Mail> getInboxList() {
        return inboxList;
    }

    public static void main(String[] args) {
        Inbox inbox = new Inbox();
    }
}