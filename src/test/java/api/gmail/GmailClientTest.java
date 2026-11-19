package api.gmail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import com.google.api.client.auth.oauth2.TokenResponseException;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpResponse;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.testing.http.MockHttpTransport;
import com.google.api.client.testing.http.MockLowLevelHttpResponse;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Pins the one-line stderr text for each failure; Cli prefixes it with "error: ". */
class GmailClientTest {
    static final JsonFactory JSON = GsonFactory.getDefaultInstance();
    static final String REAUTH = "authorization expired or revoked; delete "
            + Path.of(System.getProperty("user.home"), ".config", "gmail-api-interface", "tokens") + " and run again";

    @Test
    void revokedOrExpiredAuthorizationAsksToDeleteTheTokens() throws IOException {
        assertEquals(REAUTH, GmailClient.describe(TokenResponseException.from(JSON,
                response(400, "application/json", "{\"error\":\"invalid_grant\"}"))));
        assertEquals(REAUTH, GmailClient.describe(GoogleJsonResponseException.from(JSON,
                response(401, "application/json", "{\"error\":{\"code\":401,\"message\":\"Invalid Credentials\"}}"))));
    }

    @Test
    void otherTokenErrorsShowStatusAndErrorCodeOrReason() throws IOException {
        assertEquals("400 invalid_client", GmailClient.describe(TokenResponseException.from(JSON,
                response(400, "application/json", "{\"error\":\"invalid_client\"}"))));
        assertEquals("502 Bad Gateway", GmailClient.describe(TokenResponseException.from(JSON,
                response(502, "text/html", "<html>502</html>"))));
    }

    @Test
    void apiErrorsShowStatusAndMessageEvenWithoutAJsonBody() throws IOException {
        assertEquals("429 Too many requests", GmailClient.describe(GoogleJsonResponseException.from(JSON,
                response(429, "application/json", "{\"error\":{\"code\":429,\"message\":\"Too many requests\"}}"))));
        assertEquals("502 Bad Gateway", GmailClient.describe(GoogleJsonResponseException.from(JSON,
                response(502, "text/html", "<html>502</html>"))));
    }

    @Test
    void setupProblemsShowOnlyTheHintOtherFailuresNameTheException() {
        assertEquals("missing /x/credentials.json; see README > Setup",
                GmailClient.describe(new GmailClient.SetupException("missing /x/credentials.json")));
        assertEquals("IOException: boom", GmailClient.describe(new IOException("boom")));
    }

    @Test
    void credentialsThatAreNotADesktopClientPointToSetup(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("credentials.json");
        assertEquals("missing " + file + "; see README > Setup", setupError(file));
        for (String content : new String[] {"not json", "", "{\"web\":{\"client_id\":\"x\"}}", "{\"installed\":{}}"}) {
            Files.writeString(file, content);
            assertEquals(file + " is not a Desktop app OAuth client file; see README > Setup", setupError(file), content);
        }
        Files.delete(file);
        Files.createDirectory(file);
        assertEquals(file + " is not a Desktop app OAuth client file; see README > Setup", setupError(file));
    }

    @Test
    void unreadableCredentialsAreReportedAsSuchNotAsAWrongFile(@TempDir Path dir) throws IOException {
        assumeFalse("root".equals(System.getProperty("user.name")), "root can read a mode-000 file");
        Path file = Files.writeString(dir.resolve("credentials.json"), "{\"installed\":{\"client_id\":\"x\"}}");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("---------"));
        assertThrows(AccessDeniedException.class, () -> GmailClient.loadSecrets(file, JSON));
    }

    static String setupError(Path file) {
        return assertThrows(GmailClient.SetupException.class, () -> GmailClient.loadSecrets(file, JSON)).getMessage();
    }

    /** A real HttpResponse from the client library's own mock transport, so exceptions are built as in production. */
    static HttpResponse response(int status, String contentType, String body) throws IOException {
        var transport = new MockHttpTransport.Builder().setLowLevelHttpResponse(new MockLowLevelHttpResponse()
                .setStatusCode(status).setReasonPhrase(status == 502 ? "Bad Gateway" : null)
                .setContentType(contentType).setContent(body)).build();
        return transport.createRequestFactory().buildGetRequest(new GenericUrl("https://gmail.googleapis.com/"))
                .setThrowExceptionOnExecuteError(false).execute();
    }
}
