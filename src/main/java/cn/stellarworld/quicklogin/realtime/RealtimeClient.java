package cn.stellarworld.quicklogin.realtime;

import cn.stellarworld.quicklogin.ticket.QuickLoginTicket;
import cn.stellarworld.quicklogin.ticket.QuickLoginTicketCache;
import com.google.gson.JsonObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

public final class RealtimeClient {

    private final Logger logger;
    private final URI websocketUri;
    private final String pluginToken;
    private final String serverId;
    private final long maxTtlMillis;
    private final int reconnectDelaySeconds;
    private final int heartbeatSeconds;
    private final QuickLoginTicketCache ticketCache;
    private final ScheduledExecutorService executor;
    private final HttpClient httpClient;
    private volatile WebSocket webSocket;
    private volatile ConnectionState state = ConnectionState.DISCONNECTED;
    private volatile boolean running;
    private volatile ScheduledFuture<?> heartbeatTask;
    private volatile ScheduledFuture<?> reconnectTask;

    public RealtimeClient(
        Logger logger,
        URI websocketUri,
        String pluginToken,
        String serverId,
        long maxTtlMillis,
        int reconnectDelaySeconds,
        int heartbeatSeconds,
        QuickLoginTicketCache ticketCache
    ) {
        this.logger = logger;
        this.websocketUri = websocketUri;
        this.pluginToken = pluginToken;
        this.serverId = serverId;
        this.maxTtlMillis = maxTtlMillis;
        this.reconnectDelaySeconds = reconnectDelaySeconds;
        this.heartbeatSeconds = heartbeatSeconds;
        this.ticketCache = ticketCache;
        this.executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "StellarQuickLogin-Realtime");
            thread.setDaemon(true);
            return thread;
        });
        this.httpClient = HttpClient.newBuilder()
            .executor(executor)
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        connect();
    }

    public synchronized void stop() {
        running = false;
        state = ConnectionState.STOPPED;
        cancelHeartbeat();
        cancelReconnect();
        WebSocket socket = webSocket;
        webSocket = null;
        if (socket != null) {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "plugin shutdown");
        }
        executor.shutdownNow();
    }

    public String status() {
        return state.name().toLowerCase(Locale.ROOT);
    }

    private synchronized void connect() {
        if (!running) {
            return;
        }

        cancelReconnect();
        state = ConnectionState.CONNECTING;
        httpClient.newWebSocketBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .buildAsync(websocketUri, new Listener())
            .whenComplete((socket, throwable) -> {
                if (throwable != null) {
                    handleDisconnect("Failed to connect to StellarRealtime: " + throwable.getClass().getSimpleName());
                    return;
                }

                if (!running) {
                    socket.sendClose(WebSocket.NORMAL_CLOSURE, "plugin disabled");
                    return;
                }
                webSocket = socket;
            });
    }

    private void onOpen() {
        state = ConnectionState.CONNECTED;
        sendAuthFrame();
        scheduleHeartbeat();
    }

    private void onMessage(String rawMessage) {
        Optional<ParsedPreauthorizeMessage> parsedOptional = RealtimeMessageParser.parse(rawMessage, serverId, maxTtlMillis, System::currentTimeMillis);
        if (parsedOptional.isEmpty()) {
            return;
        }

        ParsedPreauthorizeMessage parsed = parsedOptional.get();
        if (parsed.ok()) {
            ticketCache.put(parsed.ticket());
            sendResult(parsed, true, parsed.status().wireStatus(), null);
            return;
        }

        sendResult(parsed, false, parsed.status().wireStatus(), parsed.message());
    }

    private void sendAuthFrame() {
        JsonObject authFrame = new JsonObject();
        authFrame.addProperty("type", "auth");
        authFrame.addProperty("role", "plugin");
        authFrame.addProperty("token", pluginToken);
        authFrame.addProperty("serverId", serverId);
        authFrame.addProperty("plugin", "StellarQuickLogin");
        sendJson(authFrame);
    }

    private void sendResult(ParsedPreauthorizeMessage parsed, boolean ok, String status, String message) {
        JsonObject response = new JsonObject();
        response.addProperty("type", "quicklogin.result");
        if (parsed.requestId() != null) {
            response.addProperty("requestId", parsed.requestId());
        }
        response.addProperty("ok", ok);
        response.addProperty("status", status);
        if (message != null && !message.isBlank()) {
            response.addProperty("message", message);
        }

        if (ok && parsed.ticket() != null) {
            QuickLoginTicket ticket = parsed.ticket();
            JsonObject payload = new JsonObject();
            if (ticket.playerName() != null) {
                payload.addProperty("playerName", ticket.playerName());
            }
            if (ticket.playerUuid() != null) {
                payload.addProperty("playerUuid", ticket.playerUuidAsString());
            }
            payload.addProperty("expiresIn", Math.max(0L, (ticket.expiresAtMillis() - System.currentTimeMillis()) / 1_000L));
            response.add("payload", payload);
        }

        sendJson(response);
    }

    private void sendJson(JsonObject payload) {
        WebSocket socket = webSocket;
        if (socket != null) {
            socket.sendText(payload.toString(), true);
        }
    }

    private synchronized void handleDisconnect(String reason) {
        if (!running) {
            return;
        }

        cancelHeartbeat();
        webSocket = null;
        state = ConnectionState.DISCONNECTED;
        logger.warning(reason + ". Reconnecting to StellarRealtime in " + reconnectDelaySeconds + " seconds.");
        reconnectTask = executor.schedule(this::connect, reconnectDelaySeconds, TimeUnit.SECONDS);
    }

    private void scheduleHeartbeat() {
        cancelHeartbeat();
        heartbeatTask = executor.scheduleAtFixedRate(() -> {
            WebSocket socket = webSocket;
            if (socket != null) {
                socket.sendPing(ByteBuffer.wrap(new byte[]{1}));
            }
        }, heartbeatSeconds, heartbeatSeconds, TimeUnit.SECONDS);
    }

    private void cancelHeartbeat() {
        ScheduledFuture<?> task = heartbeatTask;
        if (task != null) {
            task.cancel(false);
        }
        heartbeatTask = null;
    }

    private void cancelReconnect() {
        ScheduledFuture<?> task = reconnectTask;
        if (task != null) {
            task.cancel(false);
        }
        reconnectTask = null;
    }

    private enum ConnectionState {
        CONNECTING,
        CONNECTED,
        DISCONNECTED,
        STOPPED
    }

    private final class Listener implements WebSocket.Listener {

        private final StringBuilder textBuffer = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
            RealtimeClient.this.onOpen();
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            textBuffer.append(data);
            if (last) {
                String message = textBuffer.toString();
                textBuffer.setLength(0);
                executor.execute(() -> onMessage(message));
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            handleDisconnect("StellarRealtime closed the connection (" + statusCode + ")");
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            handleDisconnect("StellarRealtime websocket error: " + error.getClass().getSimpleName());
        }
    }
}
