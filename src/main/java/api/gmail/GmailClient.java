package api.gmail;

import com.google.api.client.auth.oauth2.AuthorizationCodeRequestUrl;
import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.auth.oauth2.TokenResponseException;
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.http.HttpResponseException;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.util.store.FileDataStoreFactory;
import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.GmailScopes;
import com.google.api.services.gmail.model.Message;

import java.io.IOException;
import java.io.Reader;
import java.net.BindException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Read-only Gmail access: OAuth on first use, then list and read, and what its failures mean. */
class GmailClient {
    private static final Path DIR =
            Path.of(System.getProperty("user.home"), ".config", "gmail-api-interface");
    private static final Path TOKENS = DIR.resolve("tokens");
    private static final String ME = "me"; // Gmail's alias for the signed-in user
    private final Gmail.Users.Messages messages;

    GmailClient() throws IOException {
        var http = new NetHttpTransport();
        var json = GsonFactory.getDefaultInstance();
        var secrets = loadSecrets(DIR.resolve("credentials.json"), json);
        var flow =
                new GoogleAuthorizationCodeFlow.Builder(
                                http, json, secrets, List.of(GmailScopes.GMAIL_READONLY))
                        .setDataStoreFactory(new FileDataStoreFactory(TOKENS.toFile()))
                        .setAccessType("offline")
                        .build();
        var receiver = new LoopbackReceiver();
        Credential cred =
                new AuthorizationCodeInstalledApp(flow, receiver) {
                    // the default opens a browser and prints the URL to stdout, which breaks `list
                    // | grep`
                    @Override
                    protected void onAuthorization(AuthorizationCodeRequestUrl url) {
                        url.setState(receiver.state);
                        System.err.println(
                                "Open this URL to authorize (Ctrl-C to abort):\n  "
                                        + Terminal.link(Cli.COLOR, url.build()));
                    }
                }.authorize("user");
        messages =
                new Gmail.Builder(http, json, cred)
                        .setApplicationName("gmail-api-interface")
                        .build()
                        .users()
                        .messages();
    }

    /**
     * A problem the user fixes by following README > Setup; {@link #describe} prints just its
     * message.
     */
    static class SetupException extends IOException {
        SetupException(String message) {
            super(message + "; see README > Setup");
        }
    }

    /**
     * The Desktop app OAuth client from README > Setup; a Web client file has no "installed" block.
     */
    static GoogleClientSecrets loadSecrets(Path file, JsonFactory json) throws IOException {
        if (Files.notExists(file)) throw new SetupException("missing " + file);
        try (Reader r = Files.newBufferedReader(file)) {
            var secrets = GoogleClientSecrets.load(json, r);
            if (secrets != null
                    && secrets.getInstalled() != null
                    && secrets.getInstalled().getClientId() != null) {
                return secrets;
            }
        } catch (FileSystemException e) {
            throw e; // e.g. permission denied: the file may be fine, and the exception names the
            // real problem
        } catch (IOException | RuntimeException e) {
            // not JSON, empty, or a directory; reported below
        }
        throw new SetupException(file + " is not a Desktop app OAuth client file");
    }

    List<Mail> list(int max, String query) throws IOException {
        if (query.isBlank()) query = "in:inbox"; // no query: list the inbox
        var found = messages.list(ME).setQ(query).setMaxResults((long) max).execute().getMessages();
        if (found == null) return List.of(); // Gmail leaves the field out when nothing matches
        // NOTE: keeping sequential for simplicity.
        // Batching saves round-trips, not quota (n batched calls count as n), and Gmail rate-limits
        // batches over 50.
        List<Mail> out = new ArrayList<>();
        for (var stub :
                found) { // list returns IDs only, so each message is fetched for its headers
            var request =
                    messages.get(ME, stub.getId())
                            .setFormat("metadata")
                            .setMetadataHeaders(Mail.HEADERS);
            out.add(Mail.of(request.execute()));
        }
        return out;
    }

    Message read(String id) throws IOException {
        return messages.get(ME, id).setFormat("full").execute();
    }

    /** One line for stderr instead of the library's multi-line JSON dumps. */
    static String describe(Exception e) {
        String reauth = "authorization expired or revoked; delete " + TOKENS + " and run again";
        if (e instanceof SetupException) return e.getMessage();
        if (e instanceof BindException) {
            // often another program, or a second sign-in, holds the port
            return "cannot listen on 127.0.0.1:"
                    + LoopbackReceiver.PORT
                    + " for the sign-in redirect: "
                    + e.getMessage();
        }
        if (e instanceof TokenResponseException token) {
            // token endpoint: refresh, or the first code exchange
            String error = token.getDetails() == null ? null : token.getDetails().getError();
            if ("invalid_grant".equals(error)) return reauth;
            return httpStatus(token, error);
        }
        if (e instanceof GoogleJsonResponseException api) { // Gmail API
            if (api.getStatusCode() == 401)
                return reauth; // revoked while the access token was still cached
            var details =
                    api.getDetails(); // null when the error body is not JSON (e.g. an HTML 502)
            return httpStatus(api, details == null ? null : details.getMessage());
        }
        return e.getClass().getSimpleName()
                + ": "
                + e.getMessage(); // e.g. an OAuth callback without a code
    }

    /**
     * "429 Too many requests": the status code, then the error body's own text or, failing that,
     * the HTTP reason.
     */
    private static String httpStatus(HttpResponseException e, String detail) {
        return e.getStatusCode()
                + " "
                + (detail != null ? detail : Objects.toString(e.getStatusMessage(), ""));
    }
}
