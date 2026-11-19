package api.gmail;

import com.google.api.client.extensions.java6.auth.oauth2.VerificationCodeReceiver;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * Where Google sends the browser after the consent screen: always http://127.0.0.1:27183/Callback.
 * It listens on the IPv4 loopback address. Nothing outside localhost can reach it, and it accepts
 * only a redirect that carries the {@link #state} of this run.
 */
class LoopbackReceiver implements VerificationCodeReceiver {
    /**
     * Fixed, the redirect address never changes. Unassigned at IANA and below the default
     * ephemeral ranges of Linux, Windows and macOS.
     */
    static final int PORT = 27183;

    private static final String PATH = "/Callback";

    /**
     * Goes into the sign-in URL; Google hands it back in the redirect, which is how a redirect
     * proves it is ours.
     */
    final String state = UUID.randomUUID().toString();

    private final BlockingQueue<String> accepted =
            new ArrayBlockingQueue<>(1); // the accepted redirect's query
    private HttpServer server;

    @Override
    public String getRedirectUri() throws IOException {
        // the IPv4 literal: no name lookup
        var loopback = InetAddress.getByAddress(new byte[] {127, 0, 0, 1});
        server = HttpServer.create(new InetSocketAddress(loopback, PORT), 0);
        server.createContext(PATH, this::handle);
        // one daemon thread per request: a connection that stalls cannot hold up the real
        // redirect, and cannot keep the tool from exiting
        server.setExecutor(
                request -> {
                    var thread = new Thread(request, "sign-in-redirect");
                    thread.setDaemon(true);
                    thread.start();
                });
        server.start();
        return "http://127.0.0.1:" + PORT + PATH;
    }

    private void handle(HttpExchange exchange) throws IOException {
        String query = Objects.toString(exchange.getRequestURI().getRawQuery(), "");
        boolean ours = state.equals(param(query, "state"));
        boolean answered = param(query, "code") != null || param(query, "error") != null;

        if (!ours || !answered) {
            reply(exchange, 400, "Not the sign-in redirect gmail-api-interface is waiting for.");
            return;
        }
        boolean approved = param(query, "error") == null;
        try {
            reply(
                    exchange,
                    200,
                    approved
                            ? "gmail-api-interface received the sign-in. You can close this tab"
                                    + " and go back to the terminal."
                            : "The sign-in was not completed. You can close this tab; the"
                                    + " terminal shows why.");
        } finally {
            // after the reply, because waitForCode returns next and the server is stopped;
            // in finally, so the sign-in still counts if the browser hung up before reading it
            accepted.offer(query);
        }
    }

    @Override
    public String waitForCode() throws IOException {
        String query;
        try {
            query = accepted.take();
        } catch (InterruptedException e) {
            throw new IOException("interrupted while waiting for the sign-in", e);
        }
        String error = param(query, "error");
        if (error != null) throw new IOException("User authorization failed (" + error + ")");
        return param(query, "code");
    }

    @Override
    public void stop() {
        // null when the sign-in never started: stored tokens were enough, or the port was busy
        if (server != null) server.stop(0);
    }

    /** The decoded value of one query parameter, or null when it is absent. */
    private static String param(String query, String name) {
        for (String pair : query.split("&")) {
            if (pair.startsWith(name + "=")) {
                return URLDecoder.decode(pair.substring(name.length() + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static void reply(HttpExchange exchange, int status, String text) throws IOException {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
