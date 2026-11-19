package api.gmail;

import java.io.IOException;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Objects;
import java.util.logging.LogManager;

/** Command-line entry point: arguments, stdout/stderr and exit codes. */
public class Cli {

    static final String USAGE =
            """
            usage: gmail-api-interface [list [N] [QUERY...] | read ID]
              list [N] [QUERY...]  newest N messages (default 20, 1..100): the inbox,
                                   or all mail matching QUERY (Gmail search syntax)
              read ID              print one message as plain text
            To search for a number, give N first: list 20 2024
            Colour is used only on a terminal; set NO_COLOR=1 to turn it off.
            """;

    /** Colour only on a terminal, and not when NO_COLOR is set or the terminal is dumb. */
    static final boolean COLOR =
            System.console() != null
                    && Objects.toString(System.getenv("NO_COLOR"), "").isEmpty()
                    && !"dumb".equals(System.getenv("TERM"));

    public static void main(String[] args) {
        LogManager.getLogManager()
                .reset(); // the client library logs refresh failures with stack traces

        if (args.length == 0) args = new String[] {"list"}; // no arguments: list the inbox

        String cmd = args[0];
        String[] rest = Arrays.copyOfRange(args, 1, args.length);
        try {
            switch (cmd) {
                case "-h", "--help", "help" -> System.out.print(USAGE);
                case "list" -> list(rest);
                case "read" -> read(rest);
                default -> exitWithUsage("unknown command: " + cmd);
            }
        } catch (IOException | RuntimeException e) {
            exitWithError(GmailClient.describe(e));
        }
    }

    /** list [N] [QUERY...]; arguments are checked before sign-in. */
    private static void list(String[] args) throws IOException {
        int max = 20;
        int queryStart = 0;
        if (args.length > 0 && args[0].matches("-?\\d+")) {
            // a leading number is N, the rest is the query 5 or more characters cannot be 1..100
            // and may not fit an int; 0 fails the range check below
            max = args[0].length() > 4 ? 0 : Integer.parseInt(args[0]);
            queryStart = 1;
        }
        if (max < 1 || max > 100) exitWithUsage("N must be 1..100");

        String query = String.join(" ", Arrays.copyOfRange(args, queryStart, args.length)).strip();
        for (Mail mail : new GmailClient().list(max, query)) {
            System.out.println(mail.line(ZoneId.systemDefault(), COLOR));
        }
    }

    /** read ID; the argument is checked before sign-in. */
    private static void read(String[] args) throws IOException {
        if (args.length != 1 || args[0].isBlank()) exitWithUsage("read needs exactly one ID");
        System.out.print(Mail.show(new GmailClient().read(args[0]), COLOR));
    }

    private static void exitWithUsage(String problem) {
        System.err.print("error: " + Terminal.clean(problem) + "\n" + USAGE);
        System.exit(2);
    }

    private static void exitWithError(String problem) {
        System.err.println("error: " + Terminal.clean(problem));
        System.exit(1);
    }
}
