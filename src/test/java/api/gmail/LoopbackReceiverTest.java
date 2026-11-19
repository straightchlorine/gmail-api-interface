package api.gmail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** The sign-in redirect: fixed address, loopback only, and only this run's redirect counts. */
@Timeout(20) // a receiver that stops answering must fail the test, not hang the build
class LoopbackReceiverTest {
    final LoopbackReceiver receiver = new LoopbackReceiver();

    @AfterEach
    void stop() {
        receiver.stop();
    }

    @Test
    void theRedirectAddressIsAlwaysTheSame() throws IOException {
        assertEquals("http://127.0.0.1:27183/Callback", receiver.getRedirectUri());
    }

    @Test
    void onlyARedirectWithThisRunsStateDeliversTheCode() throws Exception {
        receiver.getRedirectUri();
        assertEquals(400, get("").statusCode());
        assertEquals(400, get("?code=stray").statusCode());
        assertEquals(400, get("?code=stray&state=guessed").statusCode());
        assertEquals(400, get("?error=access_denied&state=guessed").statusCode());
        assertEquals(400, get("?state=" + receiver.state).statusCode());  // neither a code nor an error

        assertEquals(200, get("?state=" + receiver.state + "&code=4%2F0Abc-d_e&scope=x").statusCode());
        assertEquals("4/0Abc-d_e", receiver.waitForCode());  // not "stray": the earlier requests were not accepted
    }

    @Test
    void aCancelledSignInFailsWithGooglesReason() throws Exception {
        receiver.getRedirectUri();
        assertEquals(200, get("?error=access_denied&state=" + receiver.state).statusCode());
        var e = assertThrows(IOException.class, receiver::waitForCode);
        assertEquals("User authorization failed (access_denied)", e.getMessage());
    }

    @Test
    void aStalledConnectionDoesNotHoldUpTheRedirect() throws Exception {
        receiver.getRedirectUri();
        try (var stalled = new Socket("127.0.0.1", LoopbackReceiver.PORT)) {
            stalled.getOutputStream().write("GET /Cal".getBytes()); // a request that never finishes
            stalled.getOutputStream().flush();
            assertEquals(200, get("?code=abc&state=" + receiver.state).statusCode());
            assertEquals("abc", receiver.waitForCode());
        }
    }

    @Test
    void theSignInCountsEvenWhenThePageCannotBeWritten() throws Exception {
        receiver.getRedirectUri();
        send("HEAD", "?code=abc&state=" + receiver.state); // a HEAD reply cannot carry the page
        assertEquals("abc", receiver.waitForCode());
    }

    @Test
    void nothingButIpv4LoopbackCanConnect() throws Exception {
        receiver.getRedirectUri();
        connect(InetAddress.getByName("127.0.0.1"));
        assertThrows(IOException.class, () -> connect(InetAddress.getByName("::1")));
        // all of 127.0.0.0/8 is loopback on Linux, so this catches a listener on every address
        // even on a machine with no other network interface
        assertThrows(IOException.class, () -> connect(InetAddress.getByName("127.0.0.2")));
        for (NetworkInterface nic : NetworkInterface.networkInterfaces().toList()) {
            for (InetAddress address : nic.inetAddresses().toList()) {
                if (address.isLoopbackAddress()) continue;
                assertThrows(IOException.class, () -> connect(address), "reachable through " + address);
            }
        }
    }

    @Test
    void aBusyPortIsReportedInOneLine() throws IOException {
        var loopback = InetAddress.getByName("127.0.0.1");
        try (var other = new ServerSocket(LoopbackReceiver.PORT, 1, loopback)) {
            var e = assertThrows(BindException.class, receiver::getRedirectUri);
            String line = GmailClient.describe(e);
            assertTrue(line.startsWith("cannot listen on 127.0.0.1:27183 for the sign-in redirect: "), line);
        }
    }

    static HttpResponse<String> get(String query) throws Exception {
        return send("GET", query);
    }

    static HttpResponse<String> send(String method, String query) throws Exception {
        var request =
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:27183/Callback" + query))
                        .method(method, HttpRequest.BodyPublishers.noBody())
                        .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    static void connect(InetAddress address) throws IOException {
        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress(address, LoopbackReceiver.PORT), 500);
        }
    }
}
