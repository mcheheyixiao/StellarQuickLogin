package cn.stellarworld.quicklogin.realtime;

import cn.stellarworld.quicklogin.ticket.QuickLoginTicketCache;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RealtimeClientTest {

    @Test
    void websocketUpgradeIncludesBearerAuthorizationHeader() throws Exception {
        try (TestWebSocketServer server = TestWebSocketServer.headerCaptureOnly()) {
            RealtimeClient client = new RealtimeClient(
                Logger.getAnonymousLogger(),
                server.websocketUri(),
                "plugin-secret",
                "survival-1",
                Duration.ofMinutes(5).toMillis(),
                30,
                30,
                new QuickLoginTicketCache(System::currentTimeMillis, Duration.ofMinutes(5).toMillis())
            );

            try {
                client.start();
                assertTrue(server.awaitHeaders(), "Expected websocket upgrade request to reach the test server");
                assertEquals("Bearer plugin-secret", server.header("authorization"));
            } finally {
                client.stop();
            }
        }
    }

    @Test
    void authFrameContainsDataServerIdAndLegacyCompatibilityFields() throws Exception {
        try (TestWebSocketServer server = TestWebSocketServer.withHandshake()) {
            RealtimeClient client = new RealtimeClient(
                Logger.getAnonymousLogger(),
                server.websocketUri(),
                "plugin-secret",
                "survival-1",
                Duration.ofMinutes(5).toMillis(),
                30,
                30,
                new QuickLoginTicketCache(System::currentTimeMillis, Duration.ofMinutes(5).toMillis())
            );

            try {
                client.start();
                assertTrue(server.awaitFirstTextFrame(), "Expected auth frame after websocket handshake");

                JsonObject authFrame = JsonParser.parseString(server.firstTextFrame()).getAsJsonObject();
                assertEquals("auth", authFrame.get("type").getAsString());
                assertEquals("plugin", authFrame.get("role").getAsString());
                assertEquals("survival-1", authFrame.get("serverId").getAsString());
                assertEquals("StellarQuickLogin", authFrame.get("plugin").getAsString());

                JsonObject data = authFrame.getAsJsonObject("data");
                assertNotNull(data);
                assertEquals("survival-1", data.get("serverId").getAsString());
                assertEquals("StellarQuickLogin", data.get("serverName").getAsString());
                assertEquals("paper", data.get("platform").getAsString());
                assertEquals("1.0.0", data.get("version").getAsString());

                assertFalse(authFrame.has("token"), "Auth frame should not expose the plugin token in the JSON payload");
            } finally {
                client.stop();
            }
        }
    }

    private static final class TestWebSocketServer implements AutoCloseable {

        private static final String WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

        private final ServerSocket serverSocket;
        private final boolean completeHandshake;
        private final CountDownLatch headerLatch = new CountDownLatch(1);
        private final CountDownLatch frameLatch = new CountDownLatch(1);
        private final Map<String, String> headers = new ConcurrentHashMap<>();
        private volatile String firstTextFrame;
        private volatile Throwable failure;
        private final Thread serverThread;

        private TestWebSocketServer(boolean completeHandshake) throws IOException {
            this.serverSocket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
            this.completeHandshake = completeHandshake;
            this.serverThread = new Thread(this::run, "RealtimeClientTestServer");
            this.serverThread.setDaemon(true);
            this.serverThread.start();
        }

        static TestWebSocketServer headerCaptureOnly() throws IOException {
            return new TestWebSocketServer(false);
        }

        static TestWebSocketServer withHandshake() throws IOException {
            return new TestWebSocketServer(true);
        }

        URI websocketUri() {
            return URI.create("ws://127.0.0.1:" + serverSocket.getLocalPort() + "/ws/plugin");
        }

        boolean awaitHeaders() throws InterruptedException {
            return headerLatch.await(5, TimeUnit.SECONDS);
        }

        boolean awaitFirstTextFrame() throws InterruptedException {
            return frameLatch.await(5, TimeUnit.SECONDS);
        }

        String header(String name) {
            return headers.get(name);
        }

        String firstTextFrame() {
            if (failure != null) {
                throw new AssertionError("Test websocket server failed", failure);
            }
            return firstTextFrame;
        }

        @Override
        public void close() throws Exception {
            serverSocket.close();
            serverThread.join(5_000L);
            if (failure != null) {
                throw new AssertionError("Test websocket server failed", failure);
            }
        }

        private void run() {
            try (Socket socket = serverSocket.accept()) {
                socket.setSoTimeout(5_000);
                InputStream inputStream = socket.getInputStream();
                OutputStream outputStream = socket.getOutputStream();
                BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.US_ASCII));

                String requestLine = reader.readLine();
                if (requestLine == null || requestLine.isBlank()) {
                    throw new IOException("Missing websocket request line");
                }

                String line;
                while ((line = reader.readLine()) != null && !line.isEmpty()) {
                    int separatorIndex = line.indexOf(':');
                    if (separatorIndex > 0) {
                        headers.put(
                            line.substring(0, separatorIndex).trim().toLowerCase(),
                            line.substring(separatorIndex + 1).trim()
                        );
                    }
                }
                headerLatch.countDown();

                if (!completeHandshake) {
                    byte[] response = "HTTP/1.1 401 Unauthorized\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
                    outputStream.write(response);
                    outputStream.flush();
                    return;
                }

                String secWebSocketKey = headers.get("sec-websocket-key");
                if (secWebSocketKey == null) {
                    throw new IOException("Missing Sec-WebSocket-Key header");
                }

                byte[] response = (
                    "HTTP/1.1 101 Switching Protocols\r\n" +
                    "Upgrade: websocket\r\n" +
                    "Connection: Upgrade\r\n" +
                    "Sec-WebSocket-Accept: " + computeAccept(secWebSocketKey) + "\r\n" +
                    "\r\n"
                ).getBytes(StandardCharsets.US_ASCII);
                outputStream.write(response);
                outputStream.flush();

                firstTextFrame = readClientTextFrame(inputStream);
                frameLatch.countDown();
            } catch (Throwable throwable) {
                failure = throwable;
                headerLatch.countDown();
                frameLatch.countDown();
            }
        }

        private static String computeAccept(String secWebSocketKey) throws Exception {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] hash = digest.digest((secWebSocketKey + WEBSOCKET_GUID).getBytes(StandardCharsets.US_ASCII));
            return Base64.getEncoder().encodeToString(hash);
        }

        private static String readClientTextFrame(InputStream inputStream) throws IOException {
            int firstByte = inputStream.read();
            int secondByte = inputStream.read();
            if (firstByte < 0 || secondByte < 0) {
                throw new IOException("Missing websocket frame bytes");
            }

            int payloadLength = secondByte & 0x7F;
            if (payloadLength == 126) {
                payloadLength = (inputStream.read() << 8) | inputStream.read();
            } else if (payloadLength == 127) {
                throw new IOException("Test server does not support 64-bit websocket payloads");
            }

            byte[] mask = inputStream.readNBytes(4);
            byte[] payload = inputStream.readNBytes(payloadLength);
            if (mask.length != 4 || payload.length != payloadLength) {
                throw new IOException("Incomplete websocket payload");
            }

            for (int i = 0; i < payload.length; i++) {
                payload[i] = (byte) (payload[i] ^ mask[i % 4]);
            }
            return new String(payload, StandardCharsets.UTF_8);
        }
    }
}
